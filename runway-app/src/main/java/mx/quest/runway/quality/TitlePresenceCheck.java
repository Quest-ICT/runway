package mx.quest.runway.quality;

import java.util.List;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.springframework.stereotype.Component;

/**
 * The page title: present, and short enough to show whole in a search result.
 *
 * <ul>
 *   <li>{@code title.missing} — <b>ERROR</b>. A page without a title is broken, not merely weak: the browser tab,
 *       a bookmark, a shared link and every search result show the bare URL instead. It is clearly wrong on every
 *       page, which is why it is an error (and so blocks publishing on a site that enforces quality).</li>
 *   <li>{@code title.too-long} — WARNING, above {@value #MAX_LENGTH} characters: search engines cut the rest
 *       off. Nothing is broken, so it never blocks.</li>
 * </ul>
 *
 * <p>The title checked is the page's own title for the inspected locale (the editor's per-locale Title field),
 * not the {@code <title>} element, which a master may decorate with the site name.
 */
@Component
public class TitlePresenceCheck implements PageQualityCheck {

    /** Above this many characters a title is usually truncated in search results. */
    static final int MAX_LENGTH = 60;

    @Override
    public String key() {
        return "core.title";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        String title = page.page().title();
        if (title == null || title.isBlank()) {
            return List.of(Finding.error("title.missing", "the page has no title"));
        }
        int length = title.strip().length();
        if (length > MAX_LENGTH) {
            return List.of(Finding.warning("title.too-long",
                    "the title is " + length + " characters; keep it under " + MAX_LENGTH));
        }
        return List.of();
    }
}
