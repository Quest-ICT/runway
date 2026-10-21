package mx.quest.runway.page;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.site.Site;

/**
 * Resolves the locale for an <b>unprefixed</b> request through the soft ladder — preference cookie →
 * browser {@code Accept-Language} → site default → the page's primary locale — returning the first
 * step that the page is actually available in. (An explicit URL prefix is handled before this, by
 * the controller; only the soft path runs here.)
 */
public final class LocaleNegotiator {

    /** The per-site language-preference cookie is named this plus the site id (set by the {@code ?lang=} switcher). */
    public static final String PREFERENCE_COOKIE_PREFIX = "runway_lang_";

    private LocaleNegotiator() {
    }

    /** The name of {@code siteId}'s language-preference cookie. */
    public static String preferenceCookieName(String siteId) {
        return PREFERENCE_COOKIE_PREFIX + siteId;
    }

    /**
     * The locale for a request that has no page to fit — a plugin endpoint: an explicit {@code ?lang=} code, then the
     * preference cookie, then the browser's {@code Accept-Language}, then the site default. Each step counts only
     * when it names one of the site's locales, so the answer is always a locale the site serves.
     *
     * @param langParam       the {@code lang} query value (may be null)
     * @param cookieLocale    the language code from the per-site preference cookie (may be null)
     * @param acceptLanguages the browser's accepted locales in preference order (may be null or empty)
     */
    public static Locale forSite(Site site, String langParam, String cookieLocale, List<Locale> acceptLanguages) {
        Locale explicit = supported(langParam, site);
        if (explicit != null) {
            return explicit;
        }
        Locale cookie = supported(cookieLocale, site);
        if (cookie != null) {
            return cookie;
        }
        if (acceptLanguages != null) {
            for (Locale accepted : acceptLanguages) {
                Locale match = supported(accepted == null ? null : accepted.getLanguage(), site);
                if (match != null) {
                    return match;
                }
            }
        }
        return site.defaultLocale();
    }

    /**
     * The resolved locale for a {@link Routable} (a page or a source's content) within {@code site}.
     *
     * @param cookieLocale    the language code from the per-site preference cookie (may be null)
     * @param acceptLanguages the browser's accepted locales in preference order (may be empty)
     */
    public static Locale negotiate(Site site, Routable page, String cookieLocale, List<Locale> acceptLanguages) {
        Locale siteDefault = site.defaultLocale();

        Locale cookie = supported(cookieLocale, site);
        if (cookie != null && page.hasLocale(cookie, siteDefault)) {
            return cookie;
        }
        if (acceptLanguages != null) {
            for (Locale accepted : acceptLanguages) {
                Locale match = supported(accepted == null ? null : accepted.getLanguage(), site);
                if (match != null && page.hasLocale(match, siteDefault)) {
                    return match;
                }
            }
        }
        if (page.hasLocale(siteDefault, siteDefault)) {
            return siteDefault;
        }
        return page.primaryLocale(siteDefault);
    }

    /** The site locale whose language matches {@code code}, or null. */
    public static Locale supported(String code, Site site) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String language = code.trim().toLowerCase(Locale.ROOT);
        return site.locales().stream()
                .filter(l -> l.getLanguage().equals(language))
                .findFirst()
                .orElse(null);
    }
}
