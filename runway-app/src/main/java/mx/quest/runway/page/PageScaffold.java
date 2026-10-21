package mx.quest.runway.page;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the starting files for a new page. Two shapes, matching the authoring model:
 *
 * <ul>
 *   <li><b>Born from a master</b> — the page is a complete document that <em>pulls</em> the master's chrome
 *       seams at render (never a byte copy of the master): {@link #fromMaster} emits a skeleton {@code .html}
 *       with the seam anchors the master exposes + a starter {@code <main id="content">}, plus a stock
 *       {@code .th.xml} that replaces each anchor with the master's fragment via the {@code ${master}}
 *       <b>model variable</b> ({@code ~{${master} :: head}}). Passing the master through a variable (not a
 *       literal) is what keeps Thymeleaf's {@code __…__} preprocessing off the reserved template name
 *       — the same shape {@code RenderModelBuilder} already uses.</li>
 *   <li><b>Standalone</b> — a self-contained single-document page ({@link #standalone}) with no decoupled
 *       logic and no master pull; it renders on its own.</li>
 * </ul>
 *
 * <p><b>Only the seams the master actually exposes are pulled</b> (detected by a lightweight id check — the
 * head always, {@code #site-header} / {@code #site-footer} / {@code #site-scripts} when present) so a
 * new page never references a fragment its master lacks (which would fail the render). The author edits only
 * {@code #content}; the chrome anchors are refreshed from the master on every render.
 */
public final class PageScaffold {

    private PageScaffold() {
    }

    /** A generated page's two source files ({@code thxml} is {@code null} for a standalone page). */
    public record Scaffold(String html, String thxml) {
    }

    /** The optional chrome seams, in document order, that a master may expose and a page pulls. */
    private static final String[] BODY_SEAMS = {"site-header", "site-footer", "site-scripts"};

    /**
     * Builds a page born from {@code masterHtml}: a skeleton with a seam anchor per seam the master exposes
     * and a starter {@code #content}, plus the stock {@code .th.xml} that pulls those seams from the master.
     */
    public static Scaffold fromMaster(String masterHtml) {
        return fromMaster(masterHtml, "New page", "  <h1>New page</h1>\n  <p>Replace this with your content.</p>\n");
    }

    /**
     * As {@link #fromMaster(String)}, with {@code title} as the document title and {@code content} (trusted markup,
     * indented, ending in a newline) inside {@code #content}.
     */
    public static Scaffold fromMaster(String masterHtml, String title, String content) {
        String html = masterHtml == null ? "" : masterHtml;
        List<String> bodySeams = new ArrayList<>();
        for (String seam : BODY_SEAMS) {
            if (hasId(html, seam)) {
                bodySeams.add(seam);
            }
        }

        StringBuilder body = new StringBuilder();
        body.append("<!DOCTYPE html>\n<html>\n<head>\n<title>").append(title).append("</title>\n</head>\n<body>\n");
        StringBuilder logic = new StringBuilder();
        logic.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<thlogic>\n");
        // The <head> is always pulled (every master has one).
        logic.append("  <attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>\n");
        // Header seam(s) that precede the content.
        if (bodySeams.contains("site-header")) {
            body.append("<header id=\"site-header\"></header>\n");
            logic.append("  <attr sel=\"#site-header\" th:replace=\"~{${master} :: #site-header}\"/>\n");
        }
        body.append("<main id=\"content\">\n").append(content).append("</main>\n");
        // Footer / scripts seam(s) that follow the content.
        if (bodySeams.contains("site-footer")) {
            body.append("<footer id=\"site-footer\"></footer>\n");
            logic.append("  <attr sel=\"#site-footer\" th:replace=\"~{${master} :: #site-footer}\"/>\n");
        }
        if (bodySeams.contains("site-scripts")) {
            body.append("<div id=\"site-scripts\"></div>\n");
            logic.append("  <attr sel=\"#site-scripts\" th:replace=\"~{${master} :: #site-scripts}\"/>\n");
        }
        body.append("</body>\n</html>\n");
        logic.append("</thlogic>\n");
        return new Scaffold(body.toString(), logic.toString());
    }

    /** A self-contained standalone page (no master, no decoupled logic). */
    public static String standalone() {
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>New page</title>\n"
                + "</head>\n<body>\n<main>\n  <h1>New page</h1>\n  <p>Replace this with your content.</p>\n"
                + "</main>\n</body>\n</html>\n";
    }

    /** Whether {@code html} has an element with {@code id="<id>"} (either quote style). */
    private static boolean hasId(String html, String id) {
        return html.contains("id=\"" + id + "\"") || html.contains("id='" + id + "'");
    }
}
