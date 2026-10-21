package mx.quest.runway.web;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound {@code runway.http-cache.*} configuration for the {@code Cache-Control} header the kernel writes on
 * public-facing responses. It centralizes the caching policy so a CDN / browser can serve a page or asset
 * <b>without revalidating on every hit</b>, instead of the old unconditional {@code no-cache} that forced a
 * round-trip each time.
 *
 * <p>Three surfaces, three rules:
 * <ul>
 *   <li>{@link #getPublicPages() publicPages} — the default for a rendered public tenant page
 *       ({@code PageController}). A per-page {@code cacheControl} override in the page's {@code .meta.yml}
 *       wins over this value.</li>
 *   <li>{@link #getAssets() assets} — the site's static files ({@code SiteStaticController},
 *       {@code /static/**}), which change rarely and land under a fresh URL when edited.</li>
 *   <li>{@link #getPluginAssets() pluginAssets} — a plugin's bundled files ({@code /__plugin/<id>/static/**},
 *       {@code PluginStaticResourceConfig}), which change only when the plugin is upgraded or an operator edits
 *       them on disk. Before 1.0.0-rc5 they carried no rule and Spring Security's default {@code no-store} made
 *       every page view download them again.</li>
 * </ul>
 * The page-editor preview and error responses are always {@code no-store} and not configurable: a preview carries
 * unsaved work and an error body must never be replayed, so there is no safe looser value. (A
 * {@code runway.http-cache.no-store} key existed before 1.0 but was never read; it was removed in 1.0.0-rc2.)
 *
 * <p><b>Freshness vs. correctness.</b> The {@code publicPages} default carries a bounded {@code max-age}, but
 * every public page still emits a strong ETag / {@code Last-Modified} (see {@code PageValidatorService}), so a
 * content edit <em>changes the validator</em> and a revalidating client immediately sees the new page — the
 * {@code max-age} only bounds how long a CDN/browser may serve the current version <em>without asking</em>.
 * The two coexist: fresh within {@code max-age}, revalidated after. An operator who wants a page revalidated
 * on every hit sets {@code public-pages} back to {@code no-cache} (or per page, the {@code cacheControl}
 * field) — one config line either way.
 *
 * <p>Values are passed to the HTTP layer verbatim, so each must be a syntactically valid {@code Cache-Control}
 * header value; a blank value falls back to the documented default.
 */
@ConfigurationProperties("runway.http-cache")
public class RunwayHttpCacheProperties {

    /**
     * The {@code Cache-Control} value for a rendered public tenant page, unless the page overrides it with its
     * own {@code cacheControl}. Default: {@code public, max-age=300, stale-while-revalidate=60} — cacheable
     * for five minutes, then a background revalidation window (the ETag still busts the cache on any edit).
     */
    private String publicPages = "public, max-age=300, stale-while-revalidate=60";

    /**
     * The {@code Cache-Control} value for the site's public static assets ({@code /static/**}). Default:
     * {@code public, max-age=86400} — a day, since assets change rarely and an edit lands under a new URL.
     */
    private String assets = "public, max-age=86400";

    /**
     * The {@code Cache-Control} value for a plugin's bundled files ({@code /__plugin/<id>/static/**}). Default:
     * {@code public, max-age=3600} — an hour, then revalidated against a content ETag (a {@code 304} when the file
     * is unchanged). Not a day like a site's files: a plugin file keeps its name across upgrades, so after an
     * upgrade a visitor may see the previous version for up to this long, unless the plugin versions its URLs
     * ({@code ?v=<version>}). Only the directives Spring's {@code CacheControl} knows are accepted (see
     * {@code PluginStaticResourceConfig}); an unknown one falls back to this default with a warning.
     */
    private String pluginAssets = "public, max-age=3600";

    /**
     * The {@code Cache-Control} value for <b>immutable</b> static assets — those served under a
     * content-addressed / never-edited-in-place name (fonts by default; see {@link #immutableExtensions}).
     * Default: {@code public, max-age=31536000, immutable} — a year, and {@code immutable} tells the browser
     * <b>never</b> to revalidate within it (not even on a reload), so a repeat visitor pays zero round-trips
     * for the site's fonts. Safe precisely because these assets change under a <em>new</em> URL rather than in
     * place; an asset that can be overwritten in place must NOT be listed as immutable.
     */
    private String immutableAssets = "public, max-age=31536000, immutable";

    /**
     * The file extensions (lower-case, no dot) served with the {@link #immutableAssets immutable} lifetime
     * instead of the {@link #assets} default. Default: the web-font types ({@code woff2, woff, ttf, otf, eot})
     * — a font file is replaced by shipping a new file, never edited in place, so it is safe to cache forever.
     * Add fingerprinted bundle extensions ({@code css, js}) here only if the site's build hashes those
     * filenames; leave them out for plain, editable {@code site.css}/{@code app.js}.
     */
    private Set<String> immutableExtensions = normalizeExtensions(Set.of("woff2", "woff", "ttf", "otf", "eot"));

    /** The public-page rule, or the documented default when the configured value is blank. */
    public String publicPagesOrDefault() {
        return orDefault(publicPages, "public, max-age=300, stale-while-revalidate=60");
    }

    /** The static-asset rule, or the documented default when the configured value is blank. */
    public String assetsOrDefault() {
        return orDefault(assets, "public, max-age=86400");
    }

    /** The plugin-asset rule, or the documented default when the configured value is blank. */
    public String pluginAssetsOrDefault() {
        return orDefault(pluginAssets, "public, max-age=3600");
    }

    /** The immutable-asset rule, or the documented default when the configured value is blank. */
    public String immutableAssetsOrDefault() {
        return orDefault(immutableAssets, "public, max-age=31536000, immutable");
    }

    /**
     * The {@code Cache-Control} to serve a static asset at {@code relPath} (relative to the site's
     * {@code static/}): the long {@link #immutableAssetsOrDefault() immutable} lifetime when the file's
     * extension is in {@link #getImmutableExtensions()} (fonts by default), otherwise the
     * {@link #assetsOrDefault()} default. The extension test is case-insensitive; a path with no extension,
     * or an unlisted one, gets the default. Never call this for the {@code doc/} download subtree — those are
     * operator-uploaded and editable, so they stay on the mutable default.
     */
    public String cacheControlFor(String relPath) {
        return isImmutable(relPath) ? immutableAssetsOrDefault() : assetsOrDefault();
    }

    private boolean isImmutable(String relPath) {
        if (relPath == null || immutableExtensions.isEmpty()) {
            return false;
        }
        int dot = relPath.lastIndexOf('.');
        // A real extension: a dot that is after the last '/' and not the final character.
        if (dot < 0 || dot < relPath.lastIndexOf('/') || dot == relPath.length() - 1) {
            return false;
        }
        return immutableExtensions.contains(relPath.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Lower-cases, trims and de-dots the configured extensions so lookups are a plain O(1) contains. */
    private static Set<String> normalizeExtensions(Set<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String e : raw) {
            if (e == null) {
                continue;
            }
            String ext = e.strip().toLowerCase(Locale.ROOT);
            if (ext.startsWith(".")) {
                ext = ext.substring(1);
            }
            if (!ext.isEmpty()) {
                out.add(ext);
            }
        }
        return Set.copyOf(out);
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    public String getPublicPages() {
        return publicPages;
    }

    public void setPublicPages(String publicPages) {
        this.publicPages = publicPages;
    }

    public String getAssets() {
        return assets;
    }

    public void setAssets(String assets) {
        this.assets = assets;
    }

    public String getPluginAssets() {
        return pluginAssets;
    }

    public void setPluginAssets(String pluginAssets) {
        this.pluginAssets = pluginAssets;
    }

    public String getImmutableAssets() {
        return immutableAssets;
    }

    public void setImmutableAssets(String immutableAssets) {
        this.immutableAssets = immutableAssets;
    }

    public Set<String> getImmutableExtensions() {
        return immutableExtensions;
    }

    public void setImmutableExtensions(Set<String> immutableExtensions) {
        this.immutableExtensions = normalizeExtensions(immutableExtensions);
    }
}
