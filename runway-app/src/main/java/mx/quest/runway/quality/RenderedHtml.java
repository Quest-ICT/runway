package mx.quest.runway.quality;

import java.util.Locale;
import mx.quest.runway.spi.model.PageView;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Small shared helpers for the kernel's markup checks: parse a {@link PageView}'s rendered HTML and answer the
 * questions several checks ask (an element's accessible text, the content region). Package-private — a plugin
 * check does its own parsing; these are not part of the SPI.
 *
 * <p>Each check parses independently. A page is parsed a handful of times per inspection (only in the console,
 * never on a public request), which costs a few milliseconds and keeps every check a pure function of its view
 * with no shared state between threads.
 */
final class RenderedHtml {

    private RenderedHtml() {
    }

    /** The rendered page as a jsoup document (an empty document when the render produced nothing). */
    static Document parse(PageView view) {
        return Jsoup.parse(view.resolvedHtml());
    }

    /**
     * The page's <b>content region</b>: its {@code <main>} when it has one (so a master's header, nav and footer do
     * not count as the page's own words), else the {@code <body>}. Script-like elements are removed from the copy
     * returned, so their source never counts as text.
     */
    static Element contentRegion(Document doc) {
        Element main = doc.selectFirst("main");
        Element region = (main != null ? main : doc.body()).clone();
        region.select("script, style, noscript, template").remove();
        return region;
    }

    /**
     * The accessible name a screen reader would announce for {@code element}, approximated: {@code aria-label},
     * else its visible text, else the {@code alt} of an image inside it, else its {@code title}. Blank when it has
     * none. ({@code aria-labelledby} is treated as named — resolving the reference is out of scope for a check.)
     */
    static String accessibleName(Element element) {
        if (element.hasAttr("aria-labelledby") && !element.attr("aria-labelledby").isBlank()) {
            return element.attr("aria-labelledby");
        }
        String label = element.attr("aria-label").strip();
        if (!label.isEmpty()) {
            return label;
        }
        String text = element.text().strip();
        if (!text.isEmpty()) {
            return text;
        }
        for (Element img : element.select("img[alt]")) {
            String alt = img.attr("alt").strip();
            if (!alt.isEmpty()) {
                return alt;
            }
        }
        return element.attr("title").strip();
    }

    /** Lower-cases and collapses whitespace, and drops trailing punctuation ({@code "Click here!" → "click here"}). */
    static String normalize(String text) {
        String collapsed = text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return collapsed.replaceAll("[\\p{Punct}\\s…»›→]+$", "").replaceAll("^[\\p{Punct}\\s«‹←¡¿]+", "");
    }
}
