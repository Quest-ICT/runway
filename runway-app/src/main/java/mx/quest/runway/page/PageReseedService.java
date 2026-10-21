package mx.quest.runway.page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * Re-seeds a page's <b>chrome</b> from its master — refreshing the page's own copy of the master's seams
 * (the whole {@code <head>}, and {@code #site-header} / {@code #site-footer} / {@code #site-scripts}) while
 * leaving the author's {@code <main id="content">} — and every other byte — untouched.
 *
 * <p>For a page with {@code .th.xml} seams this affects the page's <b>offline copy only</b>: production pulls the
 * live master through those seams. A page <em>without</em> seams (a standalone scaffold, an imported page) serves
 * its own {@code .html}, so for it a re-seed <b>does</b> change what visitors see — which is why the write goes
 * through the editorial level ({@link #reseeded}). It exists so a page whose html
 * materialized the chrome (an imported page, or one an author pasted chrome into) can be brought back in line
 * with a changed master, and so an offline preview of the raw {@code .html} looks finished.
 *
 * <p><b>Only seams present in BOTH the page and the master are replaced</b> (a seam the master lacks, or the
 * page never had, is left alone — critique #4: the documented-but-unbuilt seams stay optional). Insertion of
 * a seam the page lacks, {@code #page-head} round-tripping, and a declarative {@code extraSeams} manifest are
 * deferred.
 *
 * <p><b>Byte-preservation.</b> The merge does not re-serialize the page through jsoup (whose HTML5
 * normalization would rewrite attributes/whitespace across the whole document, including {@code #content}).
 * Instead it parses with <b>position tracking</b> and splices the original source string, replacing only each
 * seam's exact character range with the master's fragment — so every untouched region, notably the author's
 * content, is preserved verbatim.
 *
 * <p><b>Fail-safe against malformed/hand-authored HTML.</b> A seam is spliced only when its opening AND
 * closing tags are truly <em>literal</em> in the source (never a jsoup-synthesized/implicit tag — an unclosed
 * {@code </header>} or absent {@code <head>} would otherwise report an end position at end-of-document and
 * swallow following siblings). A seam whose span would overlap the author's {@code #content} (content nested
 * inside a seam) or the page's own {@code <head>} (a body seam mis-placed in the head) is refused, as is a
 * duplicate seam id; the {@code <head>} is replaced only from a master that actually has head content. In
 * every refused case the page is left untouched — re-seed never corrupts, it only refreshes what it can prove
 * is safe.
 */
@Component
public class PageReseedService {

    /** The by-id body seams re-seeded from the master. {@code #content} is deliberately absent — never touched. */
    private static final List<String> SEAM_IDS = List.of("site-header", "site-footer", "site-scripts");

    private final MasterTemplates masters;

    public PageReseedService(MasterTemplates masters) {
        this.masters = masters;
    }

    /**
     * {@code body} with its chrome re-seeded from {@code page}'s master, or empty when it is already in sync.
     *
     * <p><b>Writes nothing.</b> A re-seed is an edit of the page's {@code .html}, so where it goes is decided by the
     * site's editorial level like any other save — {@code EditorialService#reseed} calls this under the page lock
     * and saves the result through the editorial rules (a pending revision for a non-approver on a reviewed site,
     * the quality gate on an enforced or reviewed one). Writing the live files directly would bypass the
     * review queue, the gate and the lock.
     *
     * @param page the page whose master supplies the chrome ({@link Page#template()})
     * @param body the page body to re-seed — the live one, or a pending revision's
     * @throws PageAuthoringException {@code missing-master} if the page's master does not exist
     */
    public Optional<String> reseeded(String siteId, Page page, String body) {
        String masterHtml = masters.readHtml(siteId, page.template())
                .orElseThrow(() -> new PageAuthoringException("missing-master",
                        "the master '" + page.template() + "' does not exist"));
        String merged = merge(body, masterHtml);
        return merged.equals(body) ? Optional.empty() : Optional.of(merged);
    }

    /**
     * Returns {@code pageHtml} with each chrome seam that both it and {@code masterHtml} expose replaced by the
     * master's version; every other byte (notably {@code #content}) is preserved verbatim.
     */
    static String merge(String pageHtml, String masterHtml) {
        Document page = Jsoup.parse(pageHtml, "", Parser.htmlParser().setTrackPosition(true));
        Document master = Jsoup.parse(masterHtml);

        // Two sacred spans that a seam splice must never overlap: the author's #content, and the page's own
        // <head> (so a body seam the author mis-placed inside <head> — which jsoup reparents — is not spliced
        // at its stale in-head source position). Only the head splice itself may touch the head span.
        int[] contentRange = literalRange(page.getElementById("content"));
        int[] headRange = literalRange(page.head());

        List<Splice> splices = new ArrayList<>();
        // Replace the whole <head> only when the page has a real <head> AND the master's <head> has content
        // (a fragment/empty master must not wipe the page's head to <head></head>).
        Element masterHead = master.head();
        if (headRange != null && masterHead != null && masterHead.childNodeSize() > 0
                && !overlaps(headRange, contentRange)) {
            splices.add(new Splice(headRange[0], headRange[1], masterHead.outerHtml()));
        }
        for (String id : SEAM_IDS) {
            if (isDuplicateId(page, id)) {
                continue; // ambiguous (invalid duplicate ids) — refuse rather than half-reseed
            }
            Element pageSeam = page.getElementById(id);
            Element masterSeam = master.getElementById(id);
            if (masterSeam == null) {
                continue;
            }
            int[] range = literalRange(pageSeam);
            if (range == null || overlaps(range, contentRange) || overlaps(range, headRange)) {
                continue; // absent/implicit, wraps #content, or sits inside <head> — leave the page as-is
            }
            splices.add(new Splice(range[0], range[1], masterSeam.outerHtml()));
        }
        if (splices.isEmpty()) {
            return pageHtml;
        }

        // Apply from the end backward so earlier character offsets stay valid as we splice.
        splices.sort(Comparator.comparingInt((Splice s) -> s.start).reversed());
        StringBuilder out = new StringBuilder(pageHtml);
        for (Splice splice : splices) {
            out.replace(splice.start, splice.end, splice.replacement);
        }
        return out.toString();
    }

    /**
     * The element's exact source span {@code [openTagStart, closeTagEnd)} — but only when BOTH tags are truly
     * <b>literal</b> in the source. A tag jsoup <em>synthesized</em> or <em>implied</em> (a missing {@code
     * </header>}, an absent {@code <head>}) reports {@code isTracked()==true} but {@code isImplicit()==true}
     * with an end position that runs to the end of the document — splicing that would swallow following
     * siblings (the author's {@code #content} included). Such elements are treated as not-present.
     */
    private static int[] literalRange(Element element) {
        if (element == null) {
            return null;
        }
        Range startTag = element.sourceRange();
        Range endTag = element.endSourceRange();
        if (!startTag.isTracked() || startTag.isImplicit() || !endTag.isTracked() || endTag.isImplicit()) {
            return null;
        }
        return new int[] {startTag.start().pos(), endTag.end().pos()};
    }

    /** Whether {@code a} and {@code b} overlap (either may be {@code null}, which never overlaps). */
    private static boolean overlaps(int[] a, int[] b) {
        return a != null && b != null && a[0] < b[1] && b[0] < a[1];
    }

    /** Whether more than one element in {@code doc} carries {@code id} (invalid, but seen in hand-edited HTML). */
    private static boolean isDuplicateId(Document doc, String id) {
        return doc.getElementsByAttributeValue("id", id).size() > 1;
    }

    private record Splice(int start, int end, String replacement) {
    }
}
