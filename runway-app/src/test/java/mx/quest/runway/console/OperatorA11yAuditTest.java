package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each rule of {@link OperatorA11yAudit} fires on the defect it exists for and stays quiet on the accepted fixes —
 * so the guard over the real screens ({@link OperatorA11yGuardTest}) cannot pass because a rule silently stopped
 * looking.
 */
class OperatorA11yAuditTest {

    /** A minimal clean page; {@code body} is spliced into its {@code <main>}. */
    private static String page(String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><title>t</title></head><body><main id=\"m\">" + body
                + "</main></body></html>";
    }

    private static List<String> audit(String html) {
        return OperatorA11yAudit.audit("t", html);
    }

    private static void assertOne(String html, String fragment) {
        List<String> found = audit(html);
        assertEquals(1, found.size(), "exactly one violation expected, got " + found);
        assertTrue(found.get(0).contains(fragment), found.get(0));
    }

    @Test
    void aCleanPagePasses() {
        assertEquals(List.of(), audit(page("<label for=\"a\">A</label><input id=\"a\">"
                + "<label>B <input></label><input aria-label=\"C\"><span id=\"d\">D</span><select aria-labelledby=\"d\">"
                + "</select><textarea aria-labelledby=\"d\"></textarea><input type=\"hidden\"><input type=\"submit\">"
                + "<button>Go</button><button aria-label=\"Close\"><i></i></button><a href=\"/\"><img src=x alt=\"Home\"></a>"
                + "<table><thead><tr><th scope=\"col\">H</th></tr></thead></table><iframe title=\"p\"></iframe>"
                + "<div aria-hidden=\"true\"><input></div>")));
    }

    @Test
    void theLanguageMustBeDeclared() {
        assertOne(page("").replace(" lang=\"en\"", ""), "no lang");
    }

    @Test
    void anUnlabelledControlIsFlaggedWhateverItsKind() {
        assertOne(page("<input id=\"x\" title=\"only a tooltip\">"), "unlabelled");
        assertOne(page("<select></select>"), "unlabelled");
        assertOne(page("<textarea></textarea>"), "unlabelled");
        assertOne(page("<input type=\"file\">"), "unlabelled");
        assertOne(page("<span aria-labelledby=\"nowhere\">x</span>"), "names a missing id");
        assertEquals(2, audit(page("<input aria-labelledby=\"nowhere\">")).size(), "dangling name = unlabelled too");
    }

    @Test
    void anIconOnlyButtonOrLinkNeedsAName() {
        assertOne(page("<button title=\"Expand\"><i class=\"fa\"></i></button>"), "button without an accessible name");
        assertOne(page("<a href=\"#\"><i class=\"fa\"></i></a>"), "a without an accessible name");
        assertOne(page("<a href=\"#\"><img src=x alt=\"\"></a>"), "a without an accessible name");
    }

    @Test
    void tablesNeedHeaderCellsWithScope() {
        assertOne(page("<table><tr><td>x</td></tr></table>"), "table without <th>");
        assertOne(page("<table><tr><th>x</th></tr></table>"), "without scope");
    }

    @Test
    void idsAreUniqueLabelsPointSomewhereAndThereIsOneMain() {
        assertOne(page("<span id=\"a\">1</span><span id=\"a\">2</span>"), "appears 2 times");
        List<String> dangling = audit(page("<label for=\"gone\">x</label>"));
        assertTrue(dangling.stream().anyMatch(v -> v.contains("points at no element")), dangling.toString());
        assertOne(page("<main>second</main>"), "2 <main>");
        assertOne(page("<iframe src=x></iframe>"), "iframe without a title");
    }

    @Test
    void aModalNeedsANameAndEscapeToClose() {
        assertOne(page("<div class=\"modal\" tabindex=\"-1\"></div>"), "modal without a resolvable aria-labelledby");
        assertOne(page("<h5 id=\"h\">T</h5><div class=\"modal\" aria-labelledby=\"h\"></div>"), "tabindex");
    }

    @Test
    void aConsolePageNeedsTheSkipLinkFirstAndANamedNav() {
        String chrome = "<header class=\"rw-topbar\"><a href=\"/\">Brand</a><nav id=\"navbar-menu\" aria-label=\"Menu\">"
                + "</nav></header>";
        String good = "<!DOCTYPE html><html lang=\"en\"><body><a class=\"rw-skip-link\" href=\"#m\">Skip</a>" + chrome
                + "<main id=\"m\"></main></body></html>";
        assertEquals(List.of(), audit(good));
        assertOne(good.replace("<a class=\"rw-skip-link\" href=\"#m\">Skip</a>", ""), "skip link");
        assertOne(good.replace("aria-label=\"Menu\"", ""), "named <nav>");
        assertOne(good.replace("<nav id=\"navbar-menu\" aria-label=\"Menu\"></nav>",
                "<div id=\"navbar-menu\"></div>"), "named <nav>");
    }

    @Test
    void aFragmentSkipsOnlyThePageLevelRules() {
        assertEquals(List.of(), OperatorA11yAudit.audit("t", "<div><label for=\"a\">A</label><input id=\"a\"></div>",
                false), "no lang / main / skip link required of a fragment");
        assertEquals(1, OperatorA11yAudit.audit("t", "<div><input></div>", false).size(),
                "element rules still apply");
    }
}
