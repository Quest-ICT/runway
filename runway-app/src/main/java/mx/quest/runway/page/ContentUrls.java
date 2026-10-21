package mx.quest.runway.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.SiteView;

/**
 * The <b>single authority</b> for a routable content's public URLs: canonical, {@code hreflang} alternates and
 * {@code x-default} — used by BOTH the render model / head injection ({@link RenderModelBuilder}) and the
 * sitemap (built by a plugin), so the {@code <head>} and the sitemap <em>provably agree</em> about
 * every item's URLs (the dual-path drift this class exists to kill).
 *
 * <p><b>The canonical-override asymmetry is deliberate</b>: {@link #canonicalUrl} honors a per-content
 * {@code canonical} override (the {@code <head>}'s canonical may point elsewhere — a syndicated original, a
 * consolidated variant), while {@link #alternates}/{@link #xDefault} always build <b>real</b> URLs from the
 * routing scheme — a sitemap must list addresses that actually serve, never overrides.
 */
public final class ContentUrls {

    private ContentUrls() {
    }

    /**
     * The effective canonical URL for {@code content} in {@code locale}: its own {@link Routable#canonical()
     * override} when set (an absolute URL used verbatim; a root-relative {@code /…} value resolved against
     * {@code baseUrl}), else the auto-computed locale-aware canonical.
     */
    public static String canonicalUrl(Routable content, Locale locale, Locale siteDefault, String baseUrl) {
        String override = content.canonical();
        if (override != null && !override.isBlank()) {
            return override.startsWith("/") ? baseUrl + override : override;
        }
        return baseUrl + LocaleUrls.urlFor(content, locale, siteDefault);
    }

    /**
     * The content's available locales <b>for URL purposes</b>: its declared {@link Routable#locales()}
     * <em>intersected with the site's own</em> (the router only ever serves the site's locale prefixes —
     * advertising a declared-but-unserved locale would be a URL the site 404s), or the site default alone when
     * nothing survives (the page convention — empty declaration means site-default only).
     */
    public static List<Locale> availableLocales(Routable content, SiteView site) {
        Locale siteDefault = site.defaultLocale();
        if (content.locales().isEmpty()) {
            return List.of(siteDefault);
        }
        List<Locale> served = new ArrayList<>(content.locales().size());
        for (Locale locale : content.locales()) {
            if (site.locales().contains(locale)) {
                served.add(locale);
            }
        }
        return served.isEmpty() ? List.of(siteDefault) : List.copyOf(served);
    }

    /**
     * The content's {@code hreflang} alternates — one per available (site-served) locale, each a <b>real</b>
     * routing URL (localized slug + locale prefix; never the canonical override).
     */
    public static List<Alternate> alternates(Routable content, SiteView site, String baseUrl) {
        Locale siteDefault = site.defaultLocale();
        return availableLocales(content, site).stream()
                .map(l -> new Alternate(l, baseUrl + LocaleUrls.urlFor(content, l, siteDefault)))
                .toList();
    }

    /**
     * The {@code x-default} URL: the content's primary locale's real URL — or, when the primary is a locale the
     * site does not serve, the URL of the content's <b>first available</b> locale ({@link #availableLocales}), so
     * x-default always names one of the content's own {@link #alternates}.
     *
     * <p>Why not the site default (the rule before 1.0.0): content declared {@code [fr, es]} on an {@code en}+{@code
     * es} site is available in {@code es} only, and its site-default address ({@code /slug}, unprefixed) is not an
     * address the content exists at — yet head x-default, the sitemap and {@code CatalogEntry.pathFor} (llms.txt's
     * links) all named it. Content with no surviving locale is available in the site default alone, so for it the
     * two rules agree.
     */
    public static String xDefault(Routable content, SiteView site, String baseUrl) {
        Locale siteDefault = site.defaultLocale();
        Locale primary = content.primaryLocale(siteDefault);
        List<Locale> available = availableLocales(content, site);
        if (!available.contains(primary)) {
            primary = available.get(0);
        }
        return baseUrl + LocaleUrls.urlFor(content, primary, siteDefault);
    }
}
