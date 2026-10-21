package mx.quest.runway.spi.model;

/**
 * What a request-facing surface (an {@link mx.quest.runway.spi.EndpointContributor} route, a
 * widget-backed page) declares about how it should be treated for discoverability and
 * caching. The defaults are <b>fail-safe</b>: not indexed, not in the sitemap, not cached —
 * so a surface that declares nothing can never pollute the search index or poison a shared
 * cache. Opting into indexing or caching is a deliberate act.
 *
 * <p>Note: caching a surface whose content varies per-user, per-request, or over time is an
 * isolation/correctness failure, not merely a performance miss — only declare
 * {@code cacheable} when the response is a pure function of the routed inputs.
 *
 * @param indexable  whether search/AI crawlers may index it (default {@code false})
 * @param inSitemap  whether it should be listed in the sitemap (default {@code false}). <b>Advisory:</b> the
 *                   kernel itself builds no sitemap; a sitemap contributor (a plugin) may
 *                   honour it, and nothing guarantees one is installed
 * @param cacheable  whether the response may be cached (default {@code false} = no-store)
 */
public record SurfacePosture(boolean indexable, boolean inSitemap, boolean cacheable) {

    private static final SurfacePosture SAFE = new SurfacePosture(false, false, false);

    /** The fail-safe default: not indexed, not in the sitemap, not cached. */
    public static SurfacePosture safeDefault() {
        return SAFE;
    }

    /** A surface that is indexed and listed in the sitemap, but still not cached. */
    public static SurfacePosture discoverable() {
        return new SurfacePosture(true, true, false);
    }
}
