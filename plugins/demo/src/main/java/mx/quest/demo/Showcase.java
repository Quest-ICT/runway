package mx.quest.demo;

import java.time.Instant;

/**
 * The demo's one piece of <b>routable content</b> per site: a short message an operator publishes from the demo's
 * console screen, served by {@link DemoShowcaseSource} as a page at {@value DemoShowcaseSource#PATH}. Stored as
 * {@code showcase.yml} in the plugin's per-site folder (published content — not personal data).
 *
 * @param text      the message (plain text; escaped when rendered)
 * @param updatedAt when it was last published — the {@code ContentRef.updatedAt} the page's {@code ETag} folds, so a
 *                  republished message is never answered with a stale {@code 304}
 */
public record Showcase(String text, Instant updatedAt) {

    public Showcase {
        text = text == null ? "" : text;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }
}
