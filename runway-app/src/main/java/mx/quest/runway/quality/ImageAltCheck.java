package mx.quest.runway.quality;

import java.util.List;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Images without an {@code alt} attribute.
 *
 * <p>{@code img.alt-missing} — <b>ERROR</b>. An {@code <img>} with no {@code alt} at all is announced by a
 * screen reader as its file name (or "image"), and is invisible to search: it is a defect in the markup, not a
 * stylistic choice, and the fix is always available. An <em>empty</em> {@code alt=""} is <b>not</b> flagged — it
 * is the correct way to mark a decorative image — and neither is an image hidden from assistive technology
 * ({@code aria-hidden="true"}, {@code role="presentation"} or {@code role="none"}).
 *
 * <p>The whole rendered document is checked, so an image in the master (a logo without {@code alt}) is reported on
 * every page — and, on a site that enforces quality, blocks publishing until the master is fixed once.
 */
@Component
public class ImageAltCheck implements PageQualityCheck {

    @Override
    public String key() {
        return "core.images";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        Document doc = RenderedHtml.parse(page);
        int missing = 0;
        String first = null;
        for (Element img : doc.select("img:not([alt])")) {
            if ("true".equalsIgnoreCase(img.attr("aria-hidden").strip())
                    || "presentation".equalsIgnoreCase(img.attr("role").strip())
                    || "none".equalsIgnoreCase(img.attr("role").strip())) {
                continue;
            }
            missing++;
            if (first == null) {
                first = img.attr("src");
            }
        }
        if (missing == 0) {
            return List.of();
        }
        return List.of(Finding.error("img.alt-missing",
                missing + " image(s) have no alt attribute (first: " + first + ")"));
    }
}
