package mx.quest.runway.page;

import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.site.Site;

/**
 * URL ↔ locale handling for the <b>default-unprefixed</b> scheme: the site default locale is
 * unprefixed ({@code /about}), every other locale carries a single-segment language prefix
 * ({@code /es/about}). A default-prefixed URL ({@code /en/about}) is never canonical (the controller
 * 301-redirects it to the unprefixed form).
 */
public final class LocaleUrls {

    private LocaleUrls() {
    }

    /** The result of stripping an optional leading locale segment. */
    public record Prefix(Locale locale, String restPath, boolean present) {
    }

    /**
     * Strips a leading {@code /xx} segment if it names a locale the site supports. When present,
     * {@link Prefix#locale()} is that locale and {@link Prefix#restPath()} is the remainder;
     * otherwise the whole path is the rest and the locale is absent.
     */
    public static Prefix parse(String path, Site site) {
        String p = (path == null || path.isEmpty()) ? "/" : path;
        if (p.length() < 2 || p.charAt(0) != '/') {
            return new Prefix(null, p, false);
        }
        // Take the FIRST PATH SEGMENT and match it against the site's own locales, rather than slicing a fixed
        // two characters. The old fixed slice could only ever recognise a 2-letter code, while urlFor happily
        // builds a prefix from ANY language — so a site with a 3-letter code (ISO 639-3: `haw`, `fil`, `ceb`)
        // advertised hreflang URLs that its own router then 404'd. Advertising a URL that does not resolve is
        // worse than omitting the alternate: an engine follows it and records a dead end.
        //
        // The site's locale list IS the allowlist, so this stays traversal-safe by construction: an arbitrary
        // first segment matches nothing and falls through as "no prefix".
        int slash = p.indexOf('/', 1);
        String code = (slash < 0) ? p.substring(1) : p.substring(1, slash);
        if (!code.isEmpty()) {
            Optional<Locale> locale = site.locales().stream()
                    .filter(l -> l.getLanguage().equalsIgnoreCase(code))
                    .findFirst();
            if (locale.isPresent()) {
                String rest = (slash < 0) ? "" : p.substring(slash);
                return new Prefix(locale.get(), rest.isEmpty() ? "/" : rest, true);
            }
        }
        return new Prefix(null, p, false);
    }

    /**
     * The canonical URL path for a page path in {@code locale}: unprefixed for the site default,
     * {@code /<lang><path>} otherwise. This composes the locale prefix only; the {@code pagePath}
     * is whatever the caller resolved (see the {@link Page} overload, which honors localized slugs).
     */
    public static String urlFor(String pagePath, Locale locale, Locale siteDefault) {
        return locale.equals(siteDefault) ? pagePath : "/" + locale.getLanguage() + pagePath;
    }

    /**
     * The canonical URL for a {@link Routable} (a page or a source's content) in {@code locale}, honoring
     * per-locale slug overrides: the locale's {@link Routable#pathFor(Locale) localized path} (falling back to
     * the default path) under the scheme's locale prefix. So a page with {@code localizedPaths: {es:
     * /acerca-de}} on an English-default site is {@code /about} in English and {@code /es/acerca-de} in
     * Spanish; the locale-prefix decision (unprefixed for the default, {@code /<lang>} otherwise) is
     * orthogonal to the slug override. One signature for pages and virtual content alike — the URL scheme
     * provably cannot diverge between them.
     */
    public static String urlFor(Routable routable, Locale locale, Locale siteDefault) {
        return urlFor(routable.pathFor(locale), locale, siteDefault);
    }
}
