package mx.quest.runway.spi.model;

/**
 * A site's core social-card and verification metadata, resolved by the host and handed to a
 * {@link mx.quest.runway.spi.HeadContributor} through the {@link HeadContext}. These are the lean
 * <em>kernel</em> SEO fields; richer concerns (analytics tags, consent, Content-Security-Policy) are
 * deliberately left to SEO/GEO/analytics plugins rather than baked into the core context.
 *
 * <p>Every field is optional: a blank or {@code null} value is normalized to {@code null} and means
 * "not set" (the host emits no tag for it). {@link #extraHead()} is a verbatim, operator-trusted
 * escape hatch — the host injects it as-is into the site's own {@code <head>} — whereas the structured
 * fields are attribute values a contributor must HTML-escape.
 *
 * @param ogImage            {@code og:image} URL, or {@code null}
 * @param ogType             {@code og:type} (e.g. {@code website}, {@code article}), or {@code null}
 * @param twitterCard        {@code twitter:card} (e.g. {@code summary_large_image}), or {@code null}
 * @param twitterSite        {@code twitter:site} handle (e.g. {@code @quest}), or {@code null}
 * @param googleVerification {@code google-site-verification} token, or {@code null}
 * @param bingVerification   Bing {@code msvalidate.01} token, or {@code null}
 * @param extraHead          verbatim markup appended inside {@code <head>} (operator-trusted), or {@code null}
 */
public record SeoTags(String ogImage, String ogType, String twitterCard, String twitterSite,
                      String googleVerification, String bingVerification, String extraHead) {

    private static final SeoTags EMPTY = new SeoTags(null, null, null, null, null, null, null);

    public SeoTags {
        ogImage = blankToNull(ogImage);
        ogType = blankToNull(ogType);
        twitterCard = blankToNull(twitterCard);
        twitterSite = blankToNull(twitterSite);
        googleVerification = blankToNull(googleVerification);
        bingVerification = blankToNull(bingVerification);
        extraHead = blankToNull(extraHead);
    }

    /** The shared "nothing set" instance — the host uses this when a site has no SEO configuration. */
    public static SeoTags empty() {
        return EMPTY;
    }

    /** Whether any field is set (so a contributor can skip emitting anything when nothing is configured). */
    public boolean hasAny() {
        return ogImage != null || ogType != null || twitterCard != null || twitterSite != null
                || googleVerification != null || bingVerification != null || extraHead != null;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
