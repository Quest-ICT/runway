package mx.quest.runway.spi.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The lazily-fetched <b>render payload</b> of one piece of content, for one locale — the counterpart of the
 * always-cheap {@link ContentRef} metadata. The kernel calls
 * {@link mx.quest.runway.spi.ContentSource#body(SiteView, String, java.util.Locale)} <em>only</em> for an
 * actual {@code 200} render: the conditional-request ({@code 304}) path, the sitemap, llms.txt and search
 * enumeration never see this type.
 *
 * <p>The payload reaches the content's body template (the {@link ContentRef#bodyTemplate()}) under the
 * <b>{@code ${content.*}} namespace</b> — {@code ${content.bodyHtml}}, {@code ${content.attributes['...']}} —
 * alongside the standard page-render model. The namespace is a <em>clobber guard</em> (source values can never
 * shadow kernel model variables), not a security boundary: the template renders with the full evaluation
 * context under the same first-party trust as any plugin code.
 *
 * @param bodyHtml   the content's main body markup, ready to splice into the body template ({@code null}
 *                   normalized to empty). The source owns the markup's safety exactly as a widget owns its
 *                   {@link RenderFragment} — sources render/escape their raw material (e.g. Markdown) before
 *                   returning it
 * @param attributes additional per-render string values for the template (e.g. a formatted publication date),
 *                   insertion order preserved; never {@code null} after normalization
 */
public record ContentBody(String bodyHtml, Map<String, String> attributes) {

    private static final ContentBody EMPTY = new ContentBody("", Map.of());

    public ContentBody {
        bodyHtml = (bodyHtml == null) ? "" : bodyHtml;
        attributes = normalize(attributes);
    }

    /** A body of just markup, no extra attributes. */
    public static ContentBody of(String bodyHtml) {
        return (bodyHtml == null || bodyHtml.isEmpty()) ? EMPTY : new ContentBody(bodyHtml, Map.of());
    }

    /** The empty body — a source's honest answer when the content has no renderable payload. */
    public static ContentBody empty() {
        return EMPTY;
    }

    /** Trimmed keys, blank keys dropped, null values coerced to "", order preserved (the params convention). */
    private static Map<String, String> normalize(Map<String, String> raw) {
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
}
