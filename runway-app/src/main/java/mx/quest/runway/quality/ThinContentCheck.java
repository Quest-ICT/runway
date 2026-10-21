package mx.quest.runway.quality;

import java.util.List;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.springframework.stereotype.Component;

/**
 * Thin content: fewer than {@value #MIN_WORDS} words in the page's content region (its {@code <main>}, else the
 * body; scripts and styles excluded — see {@link RenderedHtml#contentRegion}).
 *
 * <p>{@code content.thin} — INFO only. Plenty of good pages are short (contact, a form, a gallery); the note asks
 * the author whether the page says enough to be worth finding, it does not judge. A word is any run of
 * non-space characters containing a letter or digit, so punctuation and symbols do not count.
 */
@Component
public class ThinContentCheck implements PageQualityCheck {

    /** Below this many words the page is flagged as thin. */
    static final int MIN_WORDS = 150;

    @Override
    public String key() {
        return "core.content";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        String text = RenderedHtml.contentRegion(RenderedHtml.parse(page)).text();
        int words = countWords(text);
        if (words < MIN_WORDS) {
            return List.of(Finding.info("content.thin",
                    "the page has " + words + " words of content; under " + MIN_WORDS + " may be too thin"));
        }
        return List.of();
    }

    /** Counts whitespace-separated tokens that contain at least one letter or digit. */
    static int countWords(String text) {
        int count = 0;
        for (String token : text.strip().split("\\s+")) {
            if (token.codePoints().anyMatch(Character::isLetterOrDigit)) {
                count++;
            }
        }
        return count;
    }
}
