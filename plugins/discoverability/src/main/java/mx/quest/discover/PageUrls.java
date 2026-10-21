package mx.quest.discover;

import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.SiteView;

/**
 * The absolute base of the crawl-surface endpoints (sitemap, robots.txt, llms.txt). The URLs themselves come from
 * the host's content listing ({@code CatalogEntry} / {@code LocalizedUrl}, root-relative) — this plugin never
 * applies the URL scheme itself; it only prefixes the origin.
 */
final class PageUrls {

    private PageUrls() {
    }

    /**
     * The site's absolute base ({@code scheme://host[:port]}, no trailing slash): the site view's base URL — which
     * the host fills with the configured base URL, else {@code https://<the site's first host name>} — and, for a
     * site with neither (a host-less catch-all site), the request's <b>trusted origin</b>
     * ({@link RouteRequest#origin()}: the scheme and host as the host's trusted-proxy gate leaves them — a configured
     * proxy's forwarded values applied, raw values otherwise). Empty only when none is known.
     *
     * <p>Until 2.0.0 that last fallback was built from the raw {@code Host} header with the scheme read from
     * {@code X-Forwarded-Proto} — which the kernel does not pass to plugins — or else <i>guessed</i> as
     * {@code https}. The origin replaces both: no header is trusted unless the operator trusted the proxy that set
     * it, and the scheme is the one the request actually arrived on.
     */
    static String base(SiteView site, RouteRequest req) {
        String configured = site.baseUrl();
        String base = (configured != null && !configured.isBlank()) ? configured.trim() : req.origin().trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
