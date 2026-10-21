package mx.quest.runway.spi.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The <b>metadata</b> of one piece of content a {@link mx.quest.runway.spi.ContentSource} serves — everything
 * routing, the sitemap, llms.txt, search, and the {@code <head>} need <em>without ever fetching the body</em>.
 * The body itself ({@link ContentBody}) is fetched lazily, only for an actual {@code 200} render; keeping this
 * record body-free is what makes enumerating every source safe on a public, anonymous request (a sitemap must
 * never become a full-corpus disk/database read).
 *
 * <p>The dispatch surface deliberately mirrors the kernel page's, so virtual content participates in locale
 * dispatch <em>exactly</em> like a page: {@link #pathFor(Locale)} keeps the localized-override-else-default
 * fallback, {@link #hasLocale(Locale, Locale)} treats an empty {@link #locales()} as "site default only", and
 * {@link #primaryLocale(Locale)} prefers the site default. A localized path resolves to exactly one
 * (content, locale) pair and never doubles as another locale's URL.
 *
 * <p><b>Shape-only normalization.</b> This is a dependency-free boundary record: it trims and drops blank map
 * entries but performs no path-safety or reserved-prefix validation — the <b>kernel re-filters every source
 * output fail-closed</b> (published, path safety, reserved prefixes) before routing or enumeration consumes
 * it, so a misbehaving source results in omission, never disclosure.
 *
 * @param id             the source-scoped stable identity of this content (never {@code null}/blank). Like an
 *                       SPI {@code key()}, it is a persisted contract — URLs, search hits and internal links
 *                       ({@code ${urls['<sourceKey>:<id>']}}) survive a refactor only if it is stable
 * @param path           the default-locale public path, leading {@code /} (never {@code null}/blank)
 * @param localizedPaths per-language path overrides ({@code lang → path}); blank values are dropped so the
 *                       locale falls back to {@link #path} (never {@code null} after normalization)
 * @param locales        the locales this content is published in; <b>empty means the site default only</b>
 *                       (the page convention)
 * @param title          per-language titles ({@code lang → text}); {@link #titleFor(Locale)} falls back to any
 *                       present value
 * @param description    per-language descriptions, same fallback rule
 * @param activeNav      the navigation id this content highlights, or {@code null}
 * @param bodyTemplate   the full logical name of the Thymeleaf template that renders this content's body
 *                       (e.g. {@code __plugin__/blog/entry-classic}; never {@code null}/blank). Carried in
 *                       metadata — not on {@link ContentBody} — so a conditional-request ({@code 304}) can fold
 *                       the template into its validator <em>without</em> fetching the body, and so the kernel
 *                       can namespace-validate before rendering
 * @param published      whether this content is publicly visible. Sources must enumerate published content
 *                       only; the kernel drops unpublished refs fail-closed as the backstop
 * @param updatedAt      when the content last changed — drives the sitemap {@code <lastmod>} AND is
 *                       <b>load-bearing for HTTP revalidation</b>: the render ETag folds this record's state,
 *                       never the body payload, so ANY change to what {@code body()} returns (body files
 *                       included) MUST change {@code updatedAt} (or other ref metadata) — otherwise
 *                       revalidating clients receive stale {@code 304}s indefinitely. {@code null} if unknown
 *                       (then only metadata/template edits refresh caches)
 * @param author         display name credited for this content, or {@code null} when unattributed. An
 *                       EDITORIAL fact the source owns — unlike {@code updatedAt}, which the host derives
 * @param publishedAt    when this content was first published, or {@code null} if unknown. Editorial, and
 *                       deliberately NOT derived from a file mtime: a backup/restore or a redeploy rewrites
 *                       mtimes, and a publication date that moves is an untrue assertion
 * @param noindex        whether robots must be told not to index this content (same semantics as a page)
 * @param canonical      an absolute canonical URL override for the {@code <head>}, or {@code null}. The
 *                       sitemap deliberately ignores it — a sitemap lists real URLs
 * @param cacheControl   a {@code Cache-Control} override for responses, or {@code null} for the default
 * @param params         free-form per-content parameters for the body template (insertion order preserved;
 *                       never {@code null} after normalization)
 */
public record ContentRef(String id, String path, Map<String, String> localizedPaths, List<Locale> locales,
                         Map<String, String> title, Map<String, String> description, String activeNav,
                         String bodyTemplate, boolean published, Instant updatedAt, boolean noindex,
                         String canonical, String cacheControl, Map<String, String> params,
                         String author, Instant publishedAt) {

    /**
     * Canonical constructor — validates the required identity fields and shape-normalizes the collections
     * (defensive copies; trimmed keys; blank localized paths dropped). See the class contract for what is
     * deliberately <em>not</em> validated here.
     */
    public ContentRef {
        id = requireNonBlank(id, "id");
        path = requireNonBlank(path, "path");
        bodyTemplate = requireNonBlank(bodyTemplate, "bodyTemplate");
        localizedPaths = normalizeLocalized(localizedPaths);
        locales = (locales == null) ? List.of() : List.copyOf(locales);
        title = normalizeText(title);
        description = normalizeText(description);
        params = normalizeParams(params);
        // Blank byline == no byline: a form posts "" for an untouched field, and an empty author must not
        // serialize as a present-but-meaningless value or render as an empty byline.
        author = (author == null || author.isBlank()) ? null : author.trim();
    }

    /**
     * The pre-provenance canonical form, kept for binary compatibility (SPI evolution rule) so a
     * {@link mx.quest.runway.spi.ContentSource} compiled against an earlier SPI keeps loading: content with no
     * author and no publication date.
     */
    public ContentRef(String id, String path, Map<String, String> localizedPaths, List<Locale> locales,
                      Map<String, String> title, Map<String, String> description, String activeNav,
                      String bodyTemplate, boolean published, Instant updatedAt, boolean noindex,
                      String canonical, String cacheControl, Map<String, String> params) {
        this(id, path, localizedPaths, locales, title, description, activeNav, bodyTemplate, published,
                updatedAt, noindex, canonical, cacheControl, params, null, null);
    }

    /** A minimal published ref — the common construction for straightforward sources; overrides default off. */
    public static ContentRef of(String id, String path, Map<String, String> localizedPaths, List<Locale> locales,
                                Map<String, String> title, Map<String, String> description, String bodyTemplate,
                                Instant updatedAt) {
        return new ContentRef(id, path, localizedPaths, locales, title, description, null, bodyTemplate,
                true, updatedAt, false, null, null, Map.of(), null, null);
    }

    /**
     * A minimal published ref that carries a byline and a publication date — the shape a blog or article source
     * wants, so its entries can produce truthful {@code datePublished}/{@code author} rather than leaving the
     * schema layer to guess one value for every entry.
     */
    public static ContentRef of(String id, String path, Map<String, String> localizedPaths, List<Locale> locales,
                                Map<String, String> title, Map<String, String> description, String bodyTemplate,
                                Instant updatedAt, String author, Instant publishedAt) {
        return new ContentRef(id, path, localizedPaths, locales, title, description, null, bodyTemplate,
                true, updatedAt, false, null, null, Map.of(), author, publishedAt);
    }

    /** This ref's authorship and timestamps as one value for the SPI boundary. */
    public Provenance provenance() {
        return new Provenance(author, publishedAt, updatedAt);
    }

    /** The public path for {@code locale}: the localized override if any, else the default {@link #path}. */
    public String pathFor(Locale locale) {
        String localized = localizedPaths.get(locale.getLanguage());
        return localized != null ? localized : path;
    }

    /** Whether this content is available in {@code locale} (an empty {@link #locales} means site-default only). */
    public boolean hasLocale(Locale locale, Locale siteDefault) {
        return locales.isEmpty() ? locale.equals(siteDefault) : locales.contains(locale);
    }

    /** The primary locale: the site default if available, else the first declared locale. */
    public Locale primaryLocale(Locale siteDefault) {
        return (locales.isEmpty() || locales.contains(siteDefault)) ? siteDefault : locales.get(0);
    }

    /** The title for {@code locale}, falling back to any present title, else empty. */
    public String titleFor(Locale locale) {
        return localized(title, locale);
    }

    /** The description for {@code locale}, falling back to any present value, else empty. */
    public String descriptionFor(Locale locale) {
        return localized(description, locale);
    }

    // --- normalization (shape only — the kernel validates safety) ----------------------------------------------

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    /** Trims entries; drops null/blank keys and values (a blank override must fall back, not route to ""). */
    private static Map<String, String> normalizeLocalized(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        raw.forEach((lang, value) -> {
            if (lang != null && value != null && !lang.isBlank()) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    safe.put(lang.trim(), trimmed);
                }
            }
        });
        return Collections.unmodifiableMap(safe);
    }

    /** Null → empty; null keys/values dropped; values kept verbatim (text may legitimately lead with space). */
    private static Map<String, String> normalizeText(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        raw.forEach((lang, value) -> {
            if (lang != null && !lang.isBlank() && value != null) {
                clean.put(lang.trim(), value);
            }
        });
        return Collections.unmodifiableMap(clean);
    }

    /** The page convention: trimmed keys, blank keys dropped, null values coerced to "", order preserved. */
    private static Map<String, String> normalizeParams(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                String trimmedKey = key.trim();
                if (!trimmedKey.isEmpty()) {
                    clean.put(trimmedKey, value == null ? "" : value);
                }
            }
        });
        return Collections.unmodifiableMap(clean);
    }

    private static String localized(Map<String, String> map, Locale locale) {
        String value = map.get(locale.getLanguage());
        if (value != null) {
            return value;
        }
        return map.isEmpty() ? "" : map.values().iterator().next();
    }
}
