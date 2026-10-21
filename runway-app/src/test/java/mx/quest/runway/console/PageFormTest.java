package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mx.quest.runway.page.Page;
import org.junit.jupiter.api.Test;

/** The page form zips the parallel paramKey[]/paramValue[] arrays into the page's params map. */
class PageFormTest {

    @Test
    void zipsParallelParamArraysIntoTheMap() {
        PageForm form = new PageForm();
        form.setParamKey(List.of("analytics", "region"));
        form.setParamValue(List.of("UA-1", "eu"));
        Page page = form.toPage("about", false);
        assertEquals("UA-1", page.params().get("analytics"));
        assertEquals("eu", page.params().get("region"));
        assertEquals(2, page.params().size());
    }

    @Test
    void dropsBlankKeyedRowsAndTrimsKeys() {
        PageForm form = new PageForm();
        form.setParamKey(List.of("  spaced  ", "", "   "));
        form.setParamValue(List.of("v", "orphan", "alsoOrphan"));
        Page page = form.toPage("about", false);
        assertEquals("v", page.params().get("spaced"), "the key is trimmed");
        assertEquals(1, page.params().size(), "a blank/whitespace key row is dropped");
    }

    @Test
    void aMissingValueBecomesEmptyAndTheLastValueWinsOnADuplicateKey() {
        PageForm form = new PageForm();
        form.setParamKey(List.of("k", "dup", "dup"));
        form.setParamValue(List.of()); // shorter than the keys ⇒ every value defaults to ""
        Page page = form.toPage("about", false);
        assertEquals("", page.params().get("k"), "a missing value becomes empty");

        PageForm dupForm = new PageForm();
        dupForm.setParamKey(List.of("dup", "dup"));
        dupForm.setParamValue(List.of("first", "second"));
        assertEquals("second", dupForm.toPage("about", false).params().get("dup"), "the last value wins");
    }

    @Test
    void noParamRowsMeansAnEmptyParamsMap() {
        Page page = new PageForm().toPage("about", false);
        assertTrue(page.params().isEmpty());
        assertFalse(page.params().containsKey(""));
    }
}
