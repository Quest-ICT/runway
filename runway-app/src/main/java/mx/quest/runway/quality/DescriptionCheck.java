package mx.quest.runway.quality;

import java.util.List;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.springframework.stereotype.Component;

/**
 * The meta description: present and in the length range search engines show as a snippet.
 *
 * <ul>
 *   <li>{@code description.missing} — WARNING. Engines then write their own snippet from the page text; that is
 *       a missed opportunity, not a broken page, so it never blocks publishing.</li>
 *   <li>{@code description.too-short} — INFO, under {@value #MIN_LENGTH} characters: probably too thin to be
 *       chosen as the snippet.</li>
 *   <li>{@code description.too-long} — WARNING, over {@value #MAX_LENGTH} characters: the end is cut off.</li>
 * </ul>
 *
 * <p>The description checked is the page's own one for the inspected locale (the editor's per-locale
 * Description field).
 */
@Component
public class DescriptionCheck implements PageQualityCheck {

    /** Below this many characters a description is unlikely to be used as the result snippet. */
    static final int MIN_LENGTH = 50;
    /** Above this many characters a description is usually truncated. */
    static final int MAX_LENGTH = 160;

    @Override
    public String key() {
        return "core.description";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        String description = page.page().description();
        if (description == null || description.isBlank()) {
            return List.of(Finding.warning("description.missing", "the page has no meta description"));
        }
        int length = description.strip().length();
        if (length < MIN_LENGTH) {
            return List.of(Finding.info("description.too-short",
                    "the description is " + length + " characters; " + MIN_LENGTH + "–" + MAX_LENGTH + " works best"));
        }
        if (length > MAX_LENGTH) {
            return List.of(Finding.warning("description.too-long",
                    "the description is " + length + " characters; keep it under " + MAX_LENGTH));
        }
        return List.of();
    }
}
