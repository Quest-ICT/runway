package mx.quest.runway.web;

/**
 * The canonical locations of the site-reload endpoint, in one place so no other class re-types the literals.
 *
 * <p>{@code /__}-prefixed, so all three legs of reservation already apply with no new rule:
 * {@link ReservedPaths#atRequestTime} 404s it as tenant content, the enumeration view keeps it out of sitemaps,
 * and {@code PathSafety} refuses it at authoring time — a tenant can never publish a page that shadows it.
 */
public final class SiteReloadPaths {

    private SiteReloadPaths() {
    }

    /** Everything the reload endpoint owns. */
    public static final String PREFIX = "/__reload";

    /** Reload one site: {@code POST /__reload/site/<id>}. */
    public static final String SITE = PREFIX + "/site/";

    /** Reload every site: {@code POST /__reload/sites}. */
    public static final String ALL = PREFIX + "/sites";

    /** The site id in a {@link #SITE} path, or {@code null} when the path is not that shape. */
    public static String siteIdOf(String path) {
        if (path == null || !path.startsWith(SITE)) {
            return null;
        }
        String id = path.substring(SITE.length());
        return id.isEmpty() || id.indexOf('/') >= 0 ? null : id;
    }
}
