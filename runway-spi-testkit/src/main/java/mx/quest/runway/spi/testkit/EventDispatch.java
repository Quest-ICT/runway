package mx.quest.runway.spi.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.SiteEventListener;

/**
 * Delivers SPI change events to a plugin's {@link SiteEventListener}s in the kernel's order, so a plugin test can
 * exercise its listeners without a kernel — and doubles as the plugin's {@link ContentChanges}, bound to one plugin
 * id and the source keys it owns.
 *
 * <pre>{@code
 * EventDispatch events = new EventDispatch("blog", Set.of("blog"))
 *         .listener(blogSource)          // phase() SOURCE
 *         .listener(blogIndexConsumer);  // phase() CONSUMER
 * events.page("acme", "about", List.of(oldUrl), List.of());   // a deleted page
 * events.changed("acme", "blog", "hello");                   // what the plugin's own code calls
 * }</pre>
 *
 * <h2>The order it applies (the kernel's)</h2>
 * Every {@code SOURCE}-phase listener, then every {@code CONSUMER}; within a phase, registration order. For one
 * listener: {@code onSiteLifecycle}, then {@code onSettingsChanged} (only when the settings belong to this dispatch's
 * plugin), then {@code onCatalogChanged}. {@code SOURCE} listeners receive {@code currentUrls} empty, as on the kernel.
 * A callback that throws a {@link RuntimeException} or {@link LinkageError} is recorded in {@link #failures()} and the
 * next one still runs. {@link #changed} is refused from inside a callback, for a source key the plugin does not own,
 * and for an unsafe site id — the kernel's rules.
 *
 * <p>Not thread-safe across concurrent dispatches (tests drive it from one thread).
 */
public final class EventDispatch implements ContentChanges {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final String pluginId;
    private final Set<String> ownSourceKeys;
    private final List<SiteEventListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final List<CatalogChange> announced = new CopyOnWriteArrayList<>();
    private int callbackDepth;

    /**
     * @param pluginId      the plugin under test (its listeners receive its own settings changes)
     * @param ownSourceKeys the content source keys the plugin contributes ({@link #changed} accepts only these)
     */
    public EventDispatch(String pluginId, Set<String> ownSourceKeys) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.ownSourceKeys = Set.copyOf(ownSourceKeys);
    }

    /** Registers a listener (registration order is the order within its phase). */
    public EventDispatch listener(SiteEventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Every callback failure so far (the kernel logs and counts them; it never propagates them). */
    public List<Throwable> failures() {
        return List.copyOf(failures);
    }

    /** Every change the plugin announced through {@link #changed}, in order. */
    public List<CatalogChange> announced() {
        return List.copyOf(announced);
    }

    /** Delivers a site lifecycle event (and its {@code SITE} catalog change), local. */
    public void lifecycle(String siteId, SiteLifecycleEvent.Kind kind, boolean fromBackup) {
        SiteLifecycleEvent event = new SiteLifecycleEvent(safe(siteId), kind, fromBackup, true);
        deliver(event, null, new CatalogChange(siteId, CatalogChange.Cause.SITE, null, null, null, null, null, true));
    }

    /** Delivers a per-site settings save of plugin {@code ofPlugin} (its {@code SETTINGS} catalog change), local. */
    public void settings(String siteId, String ofPlugin) {
        SettingsChange settings = new SettingsChange(safe(siteId), ofPlugin, true);
        deliver(null, settings, new CatalogChange(siteId, CatalogChange.Cause.SETTINGS, null, null, ofPlugin, null,
                null, true));
    }

    /** Delivers a page change with the URLs the kernel would report (empty lists for none), local. */
    public void page(String siteId, String slug, List<LocalizedUrl> previousUrls, List<LocalizedUrl> currentUrls) {
        deliver(null, null, new CatalogChange(safe(siteId), CatalogChange.Cause.PAGE, "pages", slug, null,
                previousUrls, currentUrls, true));
    }

    /** Delivers any catalog change as given (to test a replayed, non-local change, say). */
    public void catalog(CatalogChange change) {
        deliver(null, null, change);
    }

    /**
     * The plugin's own announcement, as the kernel's {@code ContentChanges} handles it: delivered as a
     * {@code CONTENT} change attributed to this plugin, with no URLs (a test that needs URLs uses
     * {@link #catalog}).
     */
    @Override
    public void changed(String siteId, String sourceKey, String id) {
        safe(siteId);
        if (callbackDepth > 0) {
            throw new IllegalStateException("ContentChanges.changed was called from inside a SiteEventListener "
                    + "callback");
        }
        if (sourceKey == null || !ownSourceKeys.contains(sourceKey)) {
            throw new IllegalArgumentException("content source '" + sourceKey + "' is not contributed by plugin '"
                    + pluginId + "'");
        }
        CatalogChange change = new CatalogChange(siteId, CatalogChange.Cause.CONTENT, sourceKey, id, pluginId, null,
                null, true);
        announced.add(change);
        deliver(null, null, change);
    }

    private void deliver(SiteLifecycleEvent lifecycle, SettingsChange settings, CatalogChange change) {
        List<SiteEventListener> sources = new ArrayList<>();
        List<SiteEventListener> consumers = new ArrayList<>();
        for (SiteEventListener listener : listeners) {
            SiteEventListener.Phase phase;
            try {
                phase = listener.phase();
            } catch (RuntimeException | LinkageError e) {
                failures.add(e);
                phase = null;
            }
            (phase == SiteEventListener.Phase.SOURCE ? sources : consumers).add(listener);
        }
        CatalogChange sourceView = new CatalogChange(change.siteId(), change.cause(), change.sourceKey(),
                change.id(), change.pluginId(), change.previousUrls(), List.of(), change.local());
        for (SiteEventListener listener : sources) {
            invoke(listener, lifecycle, settings, sourceView);
        }
        for (SiteEventListener listener : consumers) {
            invoke(listener, lifecycle, settings, change);
        }
    }

    private void invoke(SiteEventListener listener, SiteLifecycleEvent lifecycle, SettingsChange settings,
                        CatalogChange change) {
        if (lifecycle != null) {
            guarded(() -> listener.onSiteLifecycle(lifecycle));
        }
        if (settings != null && pluginId.equals(settings.pluginId())) {
            guarded(() -> listener.onSettingsChanged(settings));
        }
        guarded(() -> listener.onCatalogChanged(change));
    }

    private void guarded(Runnable callback) {
        callbackDepth++;
        try {
            callback.run();
        } catch (RuntimeException | LinkageError e) {
            failures.add(e);
        } finally {
            callbackDepth--;
        }
    }

    private static String safe(String siteId) {
        if (siteId == null || !SAFE_ID.matcher(siteId).matches() || siteId.contains("..")) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
        return siteId;
    }
}
