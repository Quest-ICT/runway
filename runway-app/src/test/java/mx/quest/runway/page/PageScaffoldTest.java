package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.page.PageScaffold.Scaffold;
import org.junit.jupiter.api.Test;

/** The new-page scaffold generator: born-from-master pulls only the seams the master exposes (via the
 *  {@code ${master}} variable, not a literal), and standalone is a complete self-contained document. */
class PageScaffoldTest {

    @Test
    void fromMasterPullsEverySeamTheMasterExposesViaTheMasterVariable() {
        String master = "<!DOCTYPE html><html><head><title>M</title></head>"
                + "<body><header id=\"site-header\">h</header><main id=\"content\">m</main>"
                + "<footer id=\"site-footer\">f</footer><div id=\"site-scripts\"></div></body></html>";
        Scaffold scaffold = PageScaffold.fromMaster(master);

        assertTrue(scaffold.html().contains("id=\"content\""), "the page owns its #content");
        assertTrue(scaffold.html().contains("id=\"site-header\""));
        assertTrue(scaffold.html().contains("id=\"site-footer\""));
        assertTrue(scaffold.html().contains("id=\"site-scripts\""));
        assertTrue(scaffold.thxml().contains("~{${master} :: head}"), "the head is always pulled");
        assertTrue(scaffold.thxml().contains("~{${master} :: #site-header}"));
        assertTrue(scaffold.thxml().contains("~{${master} :: #site-footer}"));
        assertTrue(scaffold.thxml().contains("~{${master} :: #site-scripts}"));
        // The master is referenced through a MODEL VARIABLE, never a literal reserved name.
        assertFalse(scaffold.thxml().contains("__site__"), "no literal __…__ template name in the .th.xml");
    }

    @Test
    void fromMasterSkipsSeamsTheMasterDoesNotExpose() {
        String master = "<!DOCTYPE html><html><head></head><body><main id=\"content\">m</main></body></html>";
        Scaffold scaffold = PageScaffold.fromMaster(master);
        assertTrue(scaffold.thxml().contains(":: head}"), "head is still pulled");
        assertFalse(scaffold.thxml().contains("#site-header"), "a seam the master lacks is not pulled");
        assertFalse(scaffold.thxml().contains("#site-footer"));
        assertFalse(scaffold.html().contains("id=\"site-header\""));
    }

    @Test
    void standaloneIsACompleteDocumentWithNoLogic() {
        String html = PageScaffold.standalone();
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("<main>"));
        assertTrue(html.contains("</html>"));
    }
}
