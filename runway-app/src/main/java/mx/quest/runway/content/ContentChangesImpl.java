package mx.quest.runway.content;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import mx.quest.runway.event.SpiEventBridge;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.storage.PathSafety;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The kernel's {@link ContentChanges}, bound to one plugin (built by {@code PluginScopedServices} from the injecting
 * class's code source). A call publishes the host's {@link ContentChangedEvent}, which is the one path every
 * consumer already follows: the kernel listeners evict routing, URL maps and the catalog in their bands, plugins that
 * still listen to the host event receive it, the cluster relay fans it out, and the SPI bridge delivers
 * {@code onCatalogChanged} (cause {@code CONTENT}) last. Publication is synchronous, so all of that has happened when
 * {@link #changed} returns.
 *
 * <p>Refusals, before anything is published: an unsafe site id; a source key that is not a {@link ContentSource} this
 * plugin contributes (a plugin cannot evict, or make listeners react to, another plugin's content); a call from inside
 * a {@code SiteEventListener} callback (a re-entrant change storm).
 */
public final class ContentChangesImpl implements ContentChanges {

    private final String pluginId;
    private final Supplier<ContentSourceRegistry> registry;
    private final Function<Class<?>, Optional<String>> pluginOf;
    private final ApplicationEventPublisher events;

    /**
     * @param pluginId the plugin this instance answers for
     * @param registry the content sources, resolved at call time (sources may depend on this service)
     * @param pluginOf which plugin's own jar a class came from (empty for kernel classes)
     * @param events   where the host event is published
     */
    public ContentChangesImpl(String pluginId, Supplier<ContentSourceRegistry> registry,
                              Function<Class<?>, Optional<String>> pluginOf, ApplicationEventPublisher events) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pluginOf = Objects.requireNonNull(pluginOf, "pluginOf");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void changed(String siteId, String sourceKey, String id) {
        if (siteId == null || !PathSafety.isSafeId(siteId)) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
        if (SpiEventBridge.inCallback()) {
            throw new IllegalStateException("ContentChanges.changed was called from inside a SiteEventListener "
                    + "callback (plugin '" + pluginId + "'); announce changes from the code that made them, not from "
                    + "a reaction to a change");
        }
        ContentSource source = registry.get().byKey(sourceKey == null ? "" : sourceKey).orElseThrow(() ->
                new IllegalArgumentException("no content source '" + sourceKey + "' is registered"));
        String owner = pluginOf.apply(source.getClass()).orElse(null);
        if (!pluginId.equals(owner)) {
            throw new IllegalArgumentException("content source '" + sourceKey + "' is not contributed by plugin '"
                    + pluginId + "'" + (owner == null ? " (it is a kernel source)" : " (it belongs to '" + owner
                    + "')"));
        }
        events.publishEvent(new ContentChangedEvent(siteId, sourceKey, id));
    }
}
