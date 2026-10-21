package mx.quest.runway.spi;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;

/**
 * Contributes <b>routable content</b> to a site — pages that are not kernel {@code Page}s: blog entries,
 * product pages, any plugin-owned content that deserves its own public URL. This is the kernel's content
 * noun: <b>routing</b> serves a source's content through the exact same locale-dispatch ladder, tenant master
 * chrome, and head treatment as a real page; <b>a sitemap plugin</b> lists it in sitemap.xml (per-locale
 * {@code hreflang} alternates, {@code <lastmod>}) and llms.txt; <b>search</b> indexes it. Kernel pages
 * themselves are exposed through this same interface (the built-in pages source) — a plugin source is a
 * first-class peer, never a second-class citizen.
 *
 * <h2>The enumeration-first contract</h2>
 * <ul>
 *   <li>{@link #enumerate(SiteView)} is the primary method: the source's <b>published</b> content as cheap,
 *       <b>metadata-only</b> {@link ContentRef}s. It MUST NOT read content bodies — it runs on public,
 *       anonymous requests (the sitemap), so an eager-body implementation turns {@code /sitemap.xml} into a
 *       full-corpus disk/database read. Keep metadata separate from bodies in storage so this is trivially
 *       honest.</li>
 *   <li>{@link #body(SiteView, String, Locale)} is fetched <b>lazily, only for an actual {@code 200}
 *       render</b> — never for a conditional-request ({@code 304}) revalidation, never during
 *       enumeration.</li>
 *   <li>{@link #resolve(SiteView, String, Locale)} answers "do you serve this path?" on a routing miss (real
 *       pages always win first). The default implementation is a correct-by-construction linear scan of
 *       {@link #enumerate}; a real source SHOULD override it with an index probe — with
 *       {@link #CAP_ARBITRARY_PATHS} declared it runs on <em>every</em> unmatched request of an enabled site,
 *       so it must be cheap (an in-memory {@code path|lang → id} map, rebuilt on the source's own mutations
 *       and on site-restore events).</li>
 * </ul>
 *
 * <h2>Isolation and discipline</h2>
 * A source is <b>site-scoped and self-contained</b>: it reads only its own per-tenant subtree
 * ({@code sites/<id>/plugins/<key>/}) plus its per-tenant settings — never another plugin's data, never the
 * site's {@code templates/}, never application configuration. Content a source generates for a tenant belongs
 * to the tenant (it lives in the tenant folder and travels in the tenant's backup). Two contract points the
 * kernel cannot enforce and therefore <b>re-checks fail-closed</b> (dropping violations rather than serving
 * them): enumerate/resolve must return <b>published</b> content with <b>safe paths</b> only — the kernel
 * re-filters published, path safety, and reserved prefixes on every source output, so a misbehaving source
 * results in omission, never disclosure.
 *
 * <h2>Limits &amp; failure</h2>
 * A {@code enumerate}/{@code resolve}/{@code body}/{@code indexableText} that throws a {@code RuntimeException} or
 * {@code LinkageError} is logged (the first time with the stack, then at most once a minute), counted in
 * {@code runway.plugin.contribution.failures} and treated as empty/miss (degrade-don't-crash): a broken source
 * disappears from routing and listings but never 500s a tenant's request surface; a page whose {@code body} failed
 * renders its chrome around an empty body, uncacheable.
 *
 * <h2>Freshness: announce every change</h2>
 * The host cannot see a source's storage change. After every mutation — an item created, edited, deleted,
 * published or unpublished, or the whole corpus replaced — call
 * {@link mx.quest.runway.spi.service.ContentChanges#changed} (since SPI 0.6.0; the host's internal event is not
 * part of the contract, and a plugin that links it is refused from 1.0.0). The call evicts
 * routing, the listing, the sitemap and search on every node and notifies every
 * {@link mx.quest.runway.spi.service.SiteEventListener}. A source that keeps its own index drops it from a
 * {@link mx.quest.runway.spi.service.SiteEventListener.Phase#SOURCE SOURCE}-phase listener (its own content
 * changes, site lifecycle events, its own settings), so consumers never rebuild from a stale index.
 *
 * <h2>The body template's render model (contract)</h2>
 * The template named by {@link ContentRef#bodyTemplate()} renders with exactly these variables, and nothing else
 * is promised:
 * <ul>
 *   <li>{@code master} — the logical name of the site master template to wrap the body in
 *       ({@code th:replace="~{${master} :: …}"}); the ref's {@code master} param picks another master the tenant
 *       ships, else {@code _master};</li>
 *   <li>{@code content} — a map with {@code id}, {@code sourceKey}, {@code title} and {@code description} (in the
 *       rendered locale), {@code bodyHtml} and {@code attributes} (from {@link #body}), and {@code updatedAt}
 *       (ISO-8601, or {@code ""} when unknown);</li>
 *   <li>{@code locale} — the rendered {@link Locale};</li>
 *   <li>{@code urls} — link targets: a page's bare slug, or {@code <sourceKey>:<id>} for a source item, mapped to
 *       its root-relative URL in the rendered locale ({@code ${urls['blog:hello']}}).</li>
 * </ul>
 * The master itself renders with the full page model the tenant's templates use; that model is the tenant's
 * contract, not a plugin's. The render is the <b>site's</b> render, with the tenant's isolation: the body
 * template (and anything it pulls) reaches only that site's {@code __site__/<siteId>/…} templates, and Spring bean
 * references ({@code ${@…}}) are not available — feed the body through {@link #body} instead.
 *
 * <h2>Capability flags</h2>
 * By convention a flag constant ({@code CAP_*}) lives on the interface whose behaviour it changes, and its value is
 * namespaced by the axis ({@code content.…}). The host branches on one flag today, {@link #CAP_INDEXABLE_TEXT}
 * (search calls {@code indexableText} only on a source that declares it). {@link #CAP_ARBITRARY_PATHS} is
 * <b>reserved</b>: the kernel currently consults every source's {@code resolve} on a routing miss whether or not it
 * is declared; a later kernel may skip undeclared sources for paths outside their prefix, so declare it if your
 * source serves arbitrary paths. A flag the host does not recognise is ignored.
 *
 * <h2>Threading</h2>
 * Implementations must be <b>stateless or internally synchronized and thread-safe</b>; all three methods are
 * called concurrently from request threads.
 */
public interface ContentSource {

    /**
     * Declares that this source implements {@link #indexableText} — the search integration opts in per
     * source, so an engine never calls a default that silently returns nothing and indexes hollow documents.
     */
    String CAP_INDEXABLE_TEXT = "content.indexable-text";

    /**
     * Declares that this source serves content at <b>arbitrary site paths</b> (not only under a stable
     * prefix), so the kernel must consult {@link #resolve} on every routing miss of a site where the source
     * is active. Without this capability the kernel may skip the source for paths it can prove the source
     * cannot serve.
     */
    String CAP_ARBITRARY_PATHS = "content.arbitrary-paths";

    /**
     * This source's stable, unique key — the namespace of its content identities ({@code <key>:<id>} in URL
     * maps and head metadata) and of its per-tenant data folder. Persisted contract. A duplicate among kernel
     * sources fails startup; a plugin source whose key is blank or collides with another's is refused and reported
     * (an ERROR naming both sides, the plugin marked degraded, its other contributions still loaded) &mdash; against
     * a kernel source (such as {@code pages}) the plugin's is refused, between two plugins the later one's in load
     * order.
     *
     * @return a non-{@code null}, non-blank identifier, stable across releases
     */
    String key();

    /**
     * The source's <b>published</b> content for {@code site}, as metadata only. Called on public request
     * paths (sitemap, llms.txt, URL maps) — see the class contract: cheap, body-free, published-only. Order
     * is the source's choice but should be stable.
     *
     * @param site the site being served (never {@code null})
     * @return the published content's metadata (never {@code null}; may be empty)
     */
    List<ContentRef> enumerate(SiteView site);

    /**
     * The render payload of one piece of content, in one locale. Called <b>only</b> for an actual {@code 200}
     * render of a ref this source returned; {@code id} is a {@link ContentRef#id()} from this source and
     * {@code locale} one the ref declares.
     *
     * <p><b>Freshness contract:</b> the render ETag is computed from the ref's <em>metadata</em> — this method
     * is never consulted for a conditional-request revalidation. Any change to what this method returns MUST
     * be reflected in the ref (bump {@link ContentRef#updatedAt()}), or revalidating clients receive stale
     * {@code 304}s indefinitely. {@link mx.quest.runway.spi.service.ContentChanges#changed} refreshes
     * <em>server-side</em> derived state (indexes, caches) — it does NOT affect HTTP validators.
     *
     * @param site   the site being served (never {@code null})
     * @param id     the content identity (never {@code null})
     * @param locale the locale being rendered (never {@code null})
     * @return the body payload; {@link ContentBody#empty()} when the content has none (never {@code null})
     */
    ContentBody body(SiteView site, String id, Locale locale);

    /**
     * Resolves a request path (locale-prefix already stripped by the kernel) to this source's content, for
     * the locale the URL implies. Consulted only after real pages miss. The default scans
     * {@link #enumerate}; override with an index probe — see the class contract for the cost obligation.
     *
     * @param site   the site being served (never {@code null})
     * @param path   the request path, leading {@code /}, no locale prefix (never {@code null})
     * @param locale the locale the URL implies (the site default for a bare path; never {@code null})
     * @return the matching ref, or empty when this source does not serve {@code path}
     */
    default Optional<ContentRef> resolve(SiteView site, String path, Locale locale) {
        for (ContentRef ref : enumerate(site)) {
            if (ref.published() && path.equals(ref.pathFor(locale))) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    /**
     * The content's <b>indexable material</b> for one locale — what a search engine folds into its index
     * beyond the ref's title/description. Return it as <b>HTML or plain text</b>: engines strip HTML markup
     * (they were built to index page HTML bodies) and index the remaining prose, so a source whose native
     * format is not HTML must render it first — a Markdown-backed source returns rendered HTML, not raw
     * Markdown, or heading/emphasis/link syntax would leak into the index terms and result snippets. Called
     * only by search indexing (never on a routing/listing path), and only when {@link #CAP_INDEXABLE_TEXT} is
     * declared — the default returns empty for sources that predate or skip the search integration.
     *
     * @param site   the site being indexed (never {@code null})
     * @param id     the content identity (a {@link ContentRef#id()} of this source; never {@code null})
     * @param locale the locale being indexed (never {@code null})
     * @return the material as HTML or plain text, or empty when this content/locale has none (never
     *         {@code null})
     */
    default String indexableText(SiteView site, String id, Locale locale) {
        return "";
    }

    /**
     * The capabilities this source declares, so the host can branch on data rather than type. Defaults to
     * {@link Capabilities#none()}.
     *
     * @return the declared capabilities (never {@code null})
     */
    default Capabilities capabilities() {
        return Capabilities.none();
    }
}
