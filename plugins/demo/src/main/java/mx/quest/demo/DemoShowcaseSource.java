package mx.quest.demo;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.SiteEventListener;

/**
 * {@code ContentSource} — serves the site's {@link Showcase} as a real public page at {@value #PATH}, through the
 * kernel's own routing, the tenant's master chrome, the {@code <head>} treatment, {@code ETag}/{@code 304}, the
 * sitemap/llms.txt listing and on-site search. Nothing is served until an operator publishes a showcase from the
 * demo's console screen.
 *
 * <p>It is also a {@link SiteEventListener} in the {@link Phase#SOURCE SOURCE} phase. <b>When to use a
 * {@code SOURCE} listener:</b> when you are a content source that keeps derived state of your own (here, the
 * {@link DemoShowcaseStore}'s cached read). The kernel runs every {@code SOURCE} listener first, evicts its content
 * listing again, and only then runs the {@code CONSUMER}s (sitemaps, search indexes) — so a consumer that re-reads
 * the listing never sees a source's stale state. This one drops its cache on:
 * <ul>
 *   <li>a {@code CONTENT} change of its own source — on this node the store already holds the new value, but a change
 *       <b>replayed from another node</b> ({@code local == false}) is the only way this node learns the file
 *       changed;</li>
 *   <li>a <b>wholesale</b> lifecycle event ({@code DELETED}, {@code RESTORED}): the site's files were replaced or
 *       removed under it.</li>
 * </ul>
 * A callback only evicts — cheap and synchronous, as the contract asks (heavy work belongs on your own executor).
 */
public class DemoShowcaseSource implements ContentSource, SiteEventListener {

    /** This source's key: the namespace of its content ids ({@code runway-demo:showcase}). */
    static final String SOURCE_KEY = "runway-demo";
    /** The id of the one item this source serves per site. */
    static final String ID = "showcase";
    /** Where the showcase is served. */
    static final String PATH = "/demo/showcase";
    /** The body template: this plugin's {@code templates/showcase.html} + {@code .th.xml}. */
    static final String BODY_TEMPLATE = "__plugin__/runway-demo/showcase";

    private static final int DESCRIPTION_CHARS = 150;

    private final DemoShowcaseStore store;
    private final Messages messages;

    public DemoShowcaseSource(DemoShowcaseStore store, Messages messages) {
        this.store = store;
        this.messages = messages;
    }

    @Override
    public String key() {
        return SOURCE_KEY;
    }

    /** Metadata only and published only — it runs on anonymous public requests (the sitemap). */
    @Override
    public List<ContentRef> enumerate(SiteView site) {
        return store.current(site.id()).map(showcase -> List.of(ref(site, showcase))).orElse(List.of());
    }

    /** The cheap routing probe: one string comparison before anything is read. */
    @Override
    public Optional<ContentRef> resolve(SiteView site, String path, Locale locale) {
        if (!PATH.equals(path)) {
            return Optional.empty();
        }
        return enumerate(site).stream().filter(ref -> ref.hasLocale(locale, site.defaultLocale())).findFirst();
    }

    @Override
    public ContentBody body(SiteView site, String id, Locale locale) {
        if (!ID.equals(id)) {
            return ContentBody.empty();
        }
        return store.current(site.id()).map(showcase -> ContentBody.of(html(showcase.text())))
                .orElse(ContentBody.empty());
    }

    /** Declared with {@link #CAP_INDEXABLE_TEXT}, so on-site search indexes the showcase's text too. */
    @Override
    public String indexableText(SiteView site, String id, Locale locale) {
        return ID.equals(id) ? store.current(site.id()).map(s -> html(s.text())).orElse("") : "";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(CAP_INDEXABLE_TEXT);
    }

    private ContentRef ref(SiteView site, Showcase showcase) {
        String language = site.defaultLocale().getLanguage();
        String title = messages.site(site.id(), site.defaultLocale(), "runwaydemo.showcase.title", "Showcase");
        String text = showcase.text().strip();
        String description = text.length() > DESCRIPTION_CHARS ? text.substring(0, DESCRIPTION_CHARS) + "…" : text;
        // No locales listed: the item exists in the site's default language only. updatedAt moves on every
        // publish — the render ETag folds it, so a client revalidating gets the new text, not a stale 304.
        return ContentRef.of(ID, PATH, Map.of(), List.of(), Map.of(language, title), Map.of(language, description),
                BODY_TEMPLATE, showcase.updatedAt());
    }

    /** The plain text as HTML: escaped, one paragraph, line breaks kept. */
    static String html(String text) {
        return "<p>" + Html.escape(text.replace("\r\n", "\n")).replace("\n", "<br>\n") + "</p>";
    }

    // --- SiteEventListener (SOURCE phase) ----------------------------------------------------------------------

    @Override
    public Phase phase() {
        return Phase.SOURCE;
    }

    @Override
    public void onSiteLifecycle(SiteLifecycleEvent event) {
        if (event.wholesale()) {
            store.evict(event.siteId());
        }
    }

    @Override
    public void onCatalogChanged(CatalogChange change) {
        if (change.cause() == CatalogChange.Cause.CONTENT && SOURCE_KEY.equals(change.sourceKey())) {
            store.evict(change.siteId());
        }
    }
}
