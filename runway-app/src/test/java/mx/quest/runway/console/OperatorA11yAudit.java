package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The structural accessibility rules every rendered operator page must satisfy. It is deliberately a
 * small, offline, deterministic checker — not a full WCAG engine: it encodes the defects the accessibility audit found so
 * they cannot come back, and nothing that needs a browser (contrast, focus order at runtime, live regions).
 *
 * <p>The rules, each reported as one human-readable violation line naming the offending element:
 * <ol>
 *   <li>{@code <html lang>} is present and non-empty (screen readers pick their pronunciation from it).</li>
 *   <li>Every user-facing form control — {@code input} (except {@code hidden}/{@code submit}/{@code button}/
 *       {@code reset}/{@code image}), {@code select}, {@code textarea} — has an accessible name: a
 *       {@code <label for>} pointing at its id, a wrapping {@code <label>}, a non-empty {@code aria-label}, or an
 *       {@code aria-labelledby} whose every id exists. A {@code title} alone does not count.</li>
 *   <li>Every {@code <button>} and {@code <a>} has an accessible name: visible text, an {@code <img alt>} inside
 *       it, a non-empty {@code aria-label}, or a resolvable {@code aria-labelledby}. Icon-only controls named only
 *       by {@code title} fail.</li>
 *   <li>Every {@code <iframe>} has a {@code title} (its accessible name).</li>
 *   <li>Every {@code <table>} has at least one {@code <th>}, and every {@code <th>} declares {@code scope}.</li>
 *   <li>Every {@code <label for>} points at an element that exists.</li>
 *   <li>Element ids are unique in the rendered page.</li>
 *   <li>Every {@code aria-labelledby} names ids that exist; every Bootstrap {@code .modal} is named by an
 *       {@code aria-labelledby} and carries {@code tabindex="-1"} (Bootstrap's Escape-to-close needs it).</li>
 *   <li>The page has exactly one {@code <main>} landmark.</li>
 *   <li>On a console page (one carrying the realm topbar): a skip link to {@code <main>} is the first link in
 *       the body, and the topbar's menus sit in a {@code <nav>} with an accessible name.</li>
 * </ol>
 * Elements inside an {@code aria-hidden="true"} subtree (the login honeypot) are exempt: they are removed from
 * the accessibility tree on purpose.
 */
final class OperatorA11yAudit {

    private static final Set<String> UNNAMED_INPUT_TYPES = Set.of("hidden", "submit", "button", "reset", "image");

    private OperatorA11yAudit() {
    }

    /** Every violation found in the whole page {@code html}, prefixed with {@code where} (empty when clean). */
    static List<String> audit(String where, String html) {
        return audit(where, html, true);
    }

    /**
     * Every violation found in {@code html}. With {@code wholePage} false the markup is a FRAGMENT (a plugin panel
     * rendered on its own): the page-level rules ({@code lang}, one {@code <main>}, the skip link) are skipped
     * because the host page supplies them; every element-level rule still applies.
     */
    static List<String> audit(String where, String html, boolean wholePage) {
        Document doc = Jsoup.parse(html);
        List<String> out = new ArrayList<>();

        String lang = doc.selectFirst("html") == null ? "" : doc.selectFirst("html").attr("lang").trim();
        if (wholePage && lang.isEmpty()) {
            out.add(where + ": <html> has no lang");
        }

        Map<String, Integer> idCounts = new HashMap<>();
        for (Element e : doc.select("[id]")) {
            idCounts.merge(e.id(), 1, Integer::sum);
        }
        idCounts.forEach((id, n) -> {
            if (n > 1) {
                out.add(where + ": id \"" + id + "\" appears " + n + " times");
            }
        });

        for (Element control : doc.select("input, select, textarea")) {
            if (hidden(control)) {
                continue;
            }
            if (control.tagName().equals("input")
                    && UNNAMED_INPUT_TYPES.contains(control.attr("type").toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!controlHasName(doc, control)) {
                out.add(where + ": unlabelled " + describe(control));
            }
        }

        for (Element clickable : doc.select("button, a")) {
            if (hidden(clickable)) {
                continue;
            }
            if (!hasText(clickable) && !ariaNamed(doc, clickable)) {
                out.add(where + ": " + clickable.tagName() + " without an accessible name " + describe(clickable));
            }
        }

        for (Element frame : doc.select("iframe")) {
            if (frame.attr("title").isBlank() && !ariaNamed(doc, frame)) {
                out.add(where + ": iframe without a title " + describe(frame));
            }
        }

        for (Element table : doc.select("table")) {
            if (table.select("th").isEmpty()) {
                out.add(where + ": table without <th> " + describe(table));
            }
        }
        for (Element th : doc.select("th")) {
            if (th.attr("scope").isBlank()) {
                out.add(where + ": <th> without scope " + describe(th));
            }
        }

        for (Element label : doc.select("label[for]")) {
            if (doc.getElementById(label.attr("for")) == null) {
                out.add(where + ": <label for=\"" + label.attr("for") + "\"> points at no element");
            }
        }

        for (Element e : doc.select("[aria-labelledby]")) {
            if (!ariaNamed(doc, e) && e.attr("aria-label").isBlank()) {
                out.add(where + ": aria-labelledby=\"" + e.attr("aria-labelledby") + "\" names a missing id "
                        + describe(e));
            }
        }
        for (Element modal : doc.select(".modal")) {
            if (modal.attr("aria-labelledby").isBlank() || !ariaNamed(doc, modal)) {
                out.add(where + ": modal without a resolvable aria-labelledby " + describe(modal));
            }
            if (!"-1".equals(modal.attr("tabindex"))) {
                out.add(where + ": modal without tabindex=\"-1\" (Escape cannot close it) " + describe(modal));
            }
        }

        if (!wholePage) {
            return out;
        }
        int mains = doc.select("main").size();
        if (mains != 1) {
            out.add(where + ": " + mains + " <main> landmarks (exactly one expected)");
        }

        if (!doc.select(".rw-topbar").isEmpty()) {
            Element first = doc.body() == null ? null : doc.body().selectFirst("a[href]");
            Element main = doc.selectFirst("main");
            boolean skip = first != null && first.hasClass("rw-skip-link") && main != null && !main.id().isEmpty()
                    && first.attr("href").equals("#" + main.id());
            if (!skip) {
                out.add(where + ": the first link in the body is not a skip link to <main>");
            }
            Element menu = doc.selectFirst(".rw-topbar #navbar-menu");
            if (menu == null || menu.closest("nav") == null || !ariaNamed(doc, menu.closest("nav"))) {
                out.add(where + ": the topbar menu is not inside a named <nav>");
            }
        }
        return out;
    }

    private static boolean hidden(Element e) {
        for (Element cur = e; cur != null; cur = cur.parent()) {
            if ("true".equals(cur.attr("aria-hidden"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean controlHasName(Document doc, Element control) {
        if (ariaNamed(doc, control)) {
            return true;
        }
        if (control.closest("label") != null) {
            return true;
        }
        String id = control.id();
        return !id.isEmpty() && !doc.select("label[for]").stream().filter(l -> l.attr("for").equals(id))
                .toList().isEmpty();
    }

    private static boolean ariaNamed(Document doc, Element e) {
        if (!e.attr("aria-label").isBlank()) {
            return true;
        }
        String by = e.attr("aria-labelledby").trim();
        if (by.isEmpty()) {
            return false;
        }
        for (String id : by.split("\\s+")) {
            if (doc.getElementById(id) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasText(Element e) {
        if (!e.text().isBlank()) {
            return true;
        }
        return e.select("img[alt]").stream().anyMatch(img -> !img.attr("alt").isBlank());
    }

    private static String describe(Element e) {
        String outer = e.outerHtml().replaceAll("\\s+", " ");
        return outer.length() > 160 ? outer.substring(0, 160) + "…" : outer;
    }
}
