package mx.quest.runway.page;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.site.Site;
import org.springframework.stereotype.Component;

/**
 * Finds the published page for a request path. Locale negotiation and the canonical-URL redirects
 * are handled by the controller (with {@link LocaleUrls} / {@link LocaleNegotiator}); this just maps
 * a path to a page.
 */
@Component
public class PageRouting {

    private final PageStore pages;

    public PageRouting(PageStore pages) {
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    /** The published page whose path for the <b>site default</b> locale matches {@code path}, or empty. */
    public Optional<Page> find(Site site, String path) {
        return find(site, path, site.defaultLocale());
    }

    /**
     * The published page whose path <b>for {@code locale}</b> matches {@code path}, or empty. The
     * caller passes the locale the URL implies — the prefix locale when a {@code /xx} prefix is
     * present, else the site default — so a localized slug resolves under its own locale only.
     */
    public Optional<Page> find(Site site, String path, Locale locale) {
        return pages.findByPath(site.id(), path, locale);
    }

    /**
     * A map from each published page's stable slug to its <b>locale-correct, root-relative URL</b> for
     * {@code locale}: the page's URL in {@code locale} when it is available there, else its
     * primary-locale URL. Templates link by stable id — {@code th:href="${urls['about']}"} — so an
     * internal link follows localized slugs and the locale ladder, and <b>never 404s</b> even when the
     * target is not translated into the current locale (it falls back to the target's primary locale).
     *
     * @param site   the current site
     * @param locale the locale the linking page is being rendered in
     * @return slug → root-relative URL (never {@code null})
     */
    public Map<String, String> urlMap(Site site, Locale locale) {
        Locale siteDefault = site.defaultLocale();
        Map<String, String> urls = new HashMap<>();
        for (Page page : pages.findAll(site.id())) {
            if (page.published()) {
                Locale target = page.hasLocale(locale, siteDefault) ? locale : page.primaryLocale(siteDefault);
                urls.put(page.slug(), LocaleUrls.urlFor(page, target, siteDefault));
            }
        }
        return urls;
    }

    /**
     * The canonical path for a request path that <b>almost</b> matches a published page — differing only by a
     * trailing slash, letter case, or both — or empty when nothing matches even loosely.
     *
     * <p>Exists so a near-miss becomes a {@code 301} to the real URL instead of a {@code 404}. Exact matching
     * is safe against duplicate content (there is no {@code 200} at the variant) but turns every inbound link
     * carrying a stray slash, or a hand-typed capital, into a dead end — and those links are usually the ones
     * from someone else's site, which nobody can go back and fix.
     *
     * <p><b>Only ever called on the miss path</b>, so a normal request pays nothing for it. Returns the page's
     * own {@code pathFor(locale)}, never the caller's string, so the redirect target is always a real route
     * and cannot echo attacker-supplied text back into a {@code Location} header.
     *
     * <p>Returns empty when the path already matches exactly — the caller should have served it — so this can
     * never produce a redirect to the URL that was just requested.
     */
    public Optional<String> canonicalPathFor(Site site, String requestPath, Locale locale) {
        if (requestPath == null || requestPath.isBlank()) {
            return Optional.empty();
        }
        String wanted = normalize(requestPath);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        for (Page page : pages.findAll(site.id())) {
            if (!page.published()) {
                continue;
            }
            String actual = page.pathFor(locale);
            if (actual == null) {
                continue;                          // this page has no path in this locale — skip it, not the scan
            }
            if (requestPath.equals(actual)) {
                return Optional.empty();           // an exact match is not a near-miss
            }
            if (wanted.equals(normalize(actual))) {
                return Optional.of(actual);
            }
        }
        return Optional.empty();
    }

    /** Lower-cased with any trailing slash removed, so {@code /About/} and {@code /about} compare equal. */
    private static String normalize(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Whether an <b>unpublished page marked as permanently removed</b> owns {@code requestPath} — the case
     * {@code 410 Gone} exists for.
     *
     * <p>Kept as an opt-in per page rather than inferred from "unpublished but once published", because those
     * two intents are genuinely different and only the operator knows which one this is. An unpublish is
     * usually temporary — a page pulled while it is corrected — and answering {@code 410} for that tells a
     * search engine to drop the URL and be slow to come back for it. So the automatic reading would be wrong
     * in the common case, and wrong in the direction that costs the site its ranking.
     *
     * <p>Matched exactly, in the requested locale only. A near-miss on a tombstone stays a {@code 404}: the
     * point of {@code 410} is a definite statement about a definite URL, and guessing which URL the visitor
     * meant is the opposite of that.
     *
     * <p><b>Only ever called on the miss path.</b> A live page, and any plugin content claiming the path,
     * both answer first — real content always beats a tombstone.
     */
    public boolean isGone(Site site, String requestPath, Locale locale) {
        if (requestPath == null || requestPath.isBlank()) {
            return false;
        }
        for (Page page : pages.findAll(site.id())) {
            if (page.gone() && !page.published() && requestPath.equals(page.pathFor(locale))) {
                return true;
            }
        }
        return false;
    }
}
