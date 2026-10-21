package mx.quest.runway.seo;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SeoTags;
import mx.quest.runway.spi.model.SiteView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The kernel's own {@link HeadContributor}: emits every public page's social-card (OpenGraph +
 * Twitter Card) markup, plus the site's search-engine verification tags and its verbatim
 * extra-{@code <head>} markup. Core SEO is delivered as an ordinary contributor — discovered and
 * spliced exactly as a plugin's would be (the platform dogfooding its own extension point).
 *
 * <p>A pure function of the {@link HeadContext}. The social card is <b>derived from the page</b>
 * (its title, description and canonical URL) so that <em>every</em> page unfurls with a
 * title+description+url card even when the site has configured no SEO at all: {@code og:title},
 * {@code og:description}, {@code og:url}, {@code og:site_name}, {@code og:type} (default
 * {@code website}), {@code og:locale} (+ one {@code og:locale:alternate} per distinct hreflang
 * alternate), and the mirrored {@code twitter:*} tags with {@code twitter:card} defaulting to
 * {@code summary_large_image}. The share image ({@code og:image}/{@code twitter:image}), any
 * {@code og:type}/{@code twitter:card} override, the verification tags and the extra head all come
 * from the site's stored {@link SeoTags}; each is emitted only when set. The page's title and
 * description are already locale-resolved, so the card is per-locale for free, and {@code og:url}
 * uses the resolved canonical (honouring any per-page canonical override).
 *
 * <p>Structured values are HTML-attribute-escaped; {@link SeoTags#extraHead()} is injected verbatim —
 * an operator-trusted escape hatch for a site's own {@code <head>}, the one place raw markup is
 * intended.
 */
@Component
@Order(100) // core SEO first; plugin contributors (analytics/GEO) order after
public class SeoHeadContributor implements HeadContributor {

    @Override
    public String key() {
        return "runway-core-seo";
    }

    @Override
    public String render(HeadContext context) {
        SeoTags seo = context.seo() == null ? SeoTags.empty() : context.seo();
        PageMeta page = context.page();
        SiteView site = context.site();

        // Per-page social-card values, DERIVED so a card renders even for a site with no SEO config.
        String title = firstNonBlank(page == null ? null : page.title(),
                site == null ? null : site.displayName());
        String description = page == null ? null : page.description();
        String url = context.canonical();
        String siteName = site == null ? null : site.displayName();
        String image = seo.ogImage(); // shared by og:image + twitter:image; site-level only (no per-page image)
        boolean hasImage = image != null && !image.isBlank();
        String ogType = firstNonBlank(seo.ogType(), "website");
        // twitter:card: default to summary_large_image only when a share image exists, else summary — AND
        // downgrade an explicitly-configured summary_large_image to summary when there is no image, because a
        // large-image card with no image URL renders blank (or is dropped) on X / Slack / LinkedIn / Facebook.
        String twitterCard = firstNonBlank(seo.twitterCard(), hasImage ? "summary_large_image" : "summary");
        if (!hasImage && "summary_large_image".equalsIgnoreCase(twitterCard)) {
            twitterCard = "summary";
        }

        StringBuilder out = new StringBuilder();

        // If the operator hand-authored their own OG/Twitter card in extraHead, do NOT emit the derived one —
        // that would put two conflicting og:title/twitter:* sets in one <head>. (Configuring only an og:image /
        // og:type is not a hand-authored card, so the derived card still renders and picks those up.)
        String extraHead = seo.extraHead();
        boolean tenantOwnsCard = extraHead != null
                && (extraHead.contains("property=\"og:") || extraHead.contains("property='og:")
                        || extraHead.contains("name=\"twitter:") || extraHead.contains("name='twitter:"));

        if (!tenantOwnsCard) {
            // --- OpenGraph ---
            meta(out, "property", "og:title", title);
            meta(out, "property", "og:description", description);
            meta(out, "property", "og:url", url);
            meta(out, "property", "og:site_name", siteName);
            meta(out, "property", "og:type", ogType);
            meta(out, "property", "og:image", image);
            String ogLocale = ogLocale(context.locale());
            meta(out, "property", "og:locale", ogLocale);
            if (ogLocale != null) {
                Set<String> emitted = new LinkedHashSet<>();
                emitted.add(ogLocale); // never repeat the current locale as an alternate
                for (Alternate alt : context.alternates()) {
                    String altLocale = ogLocale(alt == null ? null : alt.locale());
                    if (altLocale != null && emitted.add(altLocale)) {
                        meta(out, "property", "og:locale:alternate", altLocale);
                    }
                }
            }

            // --- Twitter Card (mirrors OpenGraph; twitter:image falls back to og:image, explicit is fine) ---
            meta(out, "name", "twitter:card", twitterCard);
            meta(out, "name", "twitter:title", title);
            meta(out, "name", "twitter:description", description);
            meta(out, "name", "twitter:image", image);
            meta(out, "name", "twitter:site", seo.twitterSite());
        }

        // --- Search-engine verification (config-only) ---
        meta(out, "name", "google-site-verification", seo.googleVerification());
        meta(out, "name", "msvalidate.01", seo.bingVerification());

        // --- Operator-trusted verbatim head markup (the one raw-markup escape hatch) ---
        if (seo.extraHead() != null) {
            newlineIfNeeded(out);
            out.append(seo.extraHead());
        }
        return out.toString();
    }

    /** Appends {@code <meta name|property="key" content="escaped">} when {@code content} is set (non-blank). */
    private static void meta(StringBuilder out, String attr, String key, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        newlineIfNeeded(out);
        out.append("<meta ").append(attr).append("=\"").append(key)
                .append("\" content=\"").append(escapeAttribute(content)).append("\">");
    }

    /**
     * OpenGraph locale token: {@code language} or {@code language_COUNTRY} (e.g. {@code en},
     * {@code es_MX}); {@code null} when the locale carries no language.
     */
    private static String ogLocale(Locale locale) {
        if (locale == null) {
            return null;
        }
        String language = locale.getLanguage();
        if (language.isEmpty()) {
            return null;
        }
        String country = locale.getCountry();
        return country.isEmpty() ? language : language + "_" + country;
    }

    /** First non-blank of the two, or {@code null} if both are blank. */
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return (second != null && !second.isBlank()) ? second : null;
    }

    private static void newlineIfNeeded(StringBuilder out) {
        if (out.length() > 0) {
            out.append('\n');
        }
    }

    /** Minimal HTML attribute-context escaping so a value can never break out of the {@code content="…"}. */
    private static String escapeAttribute(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '"' -> escaped.append("&quot;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * {@code seo.yml} — the whole of this contributor's per-site input.
     *
     * <p>Without it, editing the social card or a verification token left every cached client on the previous
     * head until some unrelated page file happened to change.
     */
    @Override
    public java.util.List<String> headInputs(String siteId) {
        return java.util.List.of("seo.yml");
    }
}
