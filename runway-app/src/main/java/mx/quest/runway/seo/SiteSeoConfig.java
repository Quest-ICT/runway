package mx.quest.runway.seo;

import mx.quest.runway.spi.model.SeoTags;

/**
 * A site's stored core-SEO configuration — the lean kernel fields a site manager edits on the SEO screen
 * and that the head-injection seam emits on every public page. Persisted as {@code sites/<id>/seo.yml}
 * (inside the tenant folder, so it travels in the site's content backup with no special handling).
 *
 * <p>This is deliberately narrow: social-card hints, search-engine verification tokens, and a verbatim
 * extra-{@code <head>} escape hatch. Analytics tags, consent management and Content-Security-Policy are
 * <em>not</em> kernel concerns — they belong to SEO/GEO/analytics plugins, keeping the tenant/plugin
 * boundary honest. Canonical, {@code hreflang}/{@code x-default} and {@code robots} are emitted for free
 * by the kernel from the page's own routing and are not configured here.
 *
 * <p>Every field is optional; a blank/absent value means "not set". An absent file is {@link #EMPTY}.
 *
 * <h2>The one non-{@code <head>} field</h2>
 * {@link #documentsNoindex()} is here rather than with the Documents screen because it is an
 * <em>indexing</em> decision, and a site manager looking for "keep this out of search" looks here. It cannot
 * be expressed in markup at all: a PDF has no {@code <head>}, so the only control a search engine will honour
 * for it is the {@code X-Robots-Tag} response header, emitted by the static route rather than by the head
 * seam. Everything else on this record is markup; this one is a header — the split is in the mechanism, not
 * in what the operator is deciding.
 */
public record SiteSeoConfig(String ogImage, String ogType, String twitterCard, String twitterSite,
                            String googleVerification, String bingVerification, String extraHead,
                            boolean documentsNoindex) {

    /** The all-unset configuration — what an absent {@code seo.yml} resolves to. */
    public static final SiteSeoConfig EMPTY =
            new SiteSeoConfig(null, null, null, null, null, null, null, false);

    /** The host-materialized view handed to the head contributor (blanks normalised to {@code null}). */
    public SeoTags toTags() {
        return new SeoTags(ogImage, ogType, twitterCard, twitterSite, googleVerification, bingVerification,
                extraHead);
    }
}
