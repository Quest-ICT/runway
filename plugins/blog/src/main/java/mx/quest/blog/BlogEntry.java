package mx.quest.blog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One blog entry's <b>metadata</b> — the {@code entry.yml} document under
 * {@code sites/<siteId>/plugins/blog/entries/<id>/}. Bodies live beside it as {@code body-<lang>.md} files
 * (one per language), so enumerating entries never reads a body — the ContentSource contract's
 * metadata/body split, made trivially honest by the file layout.
 *
 * <p>URLs: the entry serves at {@code <prefix>/<slug>} (the per-tenant {@code prefix} setting), with optional
 * per-language slug overrides ({@code localizedSlugs}) and an optional <b>custom absolute path</b>
 * ({@code customPath}, e.g. {@code /announcing-v2}) that replaces the prefixed default-locale URL entirely
 * (the reason this plugin declares {@code content.arbitrary-paths}).
 *
 * @param id             the entry's stable identity — its folder name (never part of the YAML; re-applied
 *                       from the folder on read, like a page's slug)
 * @param title          per-language titles ({@code lang → text})
 * @param description    per-language descriptions
 * @param locales        the language codes this entry is published in (empty ⇒ site default only)
 * @param slug           the URL slug under the blog prefix ({@code null} ⇒ the id)
 * @param localizedSlugs per-language slug overrides ({@code lang → slug})
 * @param customPath     an absolute path override for the default locale ({@code /…}), or {@code null}
 * @param published      whether the entry is publicly visible
 * @param publishedOn    the display publication date (ISO {@code yyyy-MM-dd}), or {@code null}
 * @param author         display name credited for this entry, or {@code null} when unattributed. Free text
 *                       on purpose — a real identity model (accounts, profiles, affiliations) is a much
 *                       larger design, and blocking bylines on it would leave every entry unattributable
 *                       meanwhile. Matches the kernel's page-level author so the two cannot drift
 * @param updatedAt      newest mtime of the entry's files — derived on read, never serialized; drives the
 *                       sitemap {@code <lastmod>} AND the render ETag (re-read only after an announced change)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlogEntry(String id, Map<String, String> title, Map<String, String> description,
                        List<String> locales, String slug, Map<String, String> localizedSlugs,
                        String customPath, boolean published, String publishedOn, String author,
                        @JsonIgnore Instant updatedAt) {

    public BlogEntry {
        title = normalize(title);
        description = normalize(description);
        locales = (locales == null) ? List.of() : List.copyOf(locales);
        localizedSlugs = normalize(localizedSlugs);
        slug = blankToNull(slug);
        customPath = blankToNull(customPath);
        publishedOn = blankToNull(publishedOn);
        // Blank == unattributed: the editor posts "" for an untouched field, and an empty byline must not
        // serialize into entry.yml or render as an empty author element.
        author = blankToNull(author);
    }

    /** This entry with its stable id applied (the folder name — never stored inside the YAML). */
    public BlogEntry withId(String id) {
        return new BlogEntry(id, title, description, locales, slug, localizedSlugs, customPath, published,
                publishedOn, author, updatedAt);
    }

    /** This entry with the derived {@code updatedAt} applied (from the entry files' mtimes). */
    public BlogEntry withUpdatedAt(Instant updatedAt) {
        return new BlogEntry(id, title, description, locales, slug, localizedSlugs, customPath, published,
                publishedOn, author, updatedAt);
    }

    /** The form serialized to {@code entry.yml}: no id (the folder carries it), no derived fields. */
    public BlogEntry forStorage() {
        return new BlogEntry(null, title, description, locales, slug, localizedSlugs, customPath, published,
                publishedOn, author, null);
    }

    /** The effective URL slug under the blog prefix: the explicit {@link #slug}, else the id. */
    @JsonIgnore
    public String effectiveSlug() {
        return slug != null ? slug : id;
    }

    private static Map<String, String> normalize(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                clean.put(k.trim(), v.trim());
            }
        });
        return java.util.Collections.unmodifiableMap(clean);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
