package mx.quest.runway.quality;

import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * The heading outline of the rendered page (the whole document, master chrome included — that is what a visitor
 * and a screen reader get).
 *
 * <ul>
 *   <li>{@code h1.missing} — WARNING: no {@code <h1>}. The page has no top-level heading to tell a reader (or an
 *       engine) what it is about.</li>
 *   <li>{@code h1.multiple} — WARNING: more than one {@code <h1>} — often a master that puts the site name in an
 *       {@code <h1>} on every page.</li>
 *   <li>{@code heading.skipped} — WARNING: a heading more than one level deeper than the one before it (an
 *       {@code <h2>} followed by an {@code <h4>}), which breaks the outline assistive technology navigates by.
 *       Reported once, for the first skip.</li>
 * </ul>
 *
 * <p>All three are warnings, never errors: the page reads and works; its structure is weaker than it should be.
 * Making them block publishing would fail every page of a site whose master carries a second {@code <h1>}, for a
 * fault the page author cannot fix from the page.
 */
@Component
public class HeadingStructureCheck implements PageQualityCheck {

    @Override
    public String key() {
        return "core.headings";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        Document doc = RenderedHtml.parse(page);
        List<Finding> findings = new ArrayList<>();
        int h1 = doc.select("h1").size();
        if (h1 == 0) {
            findings.add(Finding.warning("h1.missing", "the page has no <h1> heading"));
        } else if (h1 > 1) {
            findings.add(Finding.warning("h1.multiple", "the page has " + h1 + " <h1> headings; use one"));
        }
        int previous = 0;
        for (Element heading : doc.select("h1, h2, h3, h4, h5, h6")) {
            int level = heading.tagName().charAt(1) - '0';
            if (previous > 0 && level > previous + 1) {
                findings.add(Finding.warning("heading.skipped",
                        "heading levels skip from <h" + previous + "> to <h" + level + ">"));
                break;
            }
            previous = level;
        }
        return findings;
    }
}
