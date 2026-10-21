package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The conditional-request logic: ETag matching (list/weak/star) and If-Modified-Since, with precedence. */
class PageValidatorTest {

    private static final PageValidator V = new PageValidator("\"abc\"", 1000L);

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest("GET", "/p");
    }

    @Test
    void exactEtagMatchIsNotModified() {
        MockHttpServletRequest r = req();
        r.addHeader("If-None-Match", "\"abc\"");
        assertTrue(V.isNotModified(r));
    }

    @Test
    void etagMismatchIsModified() {
        MockHttpServletRequest r = req();
        r.addHeader("If-None-Match", "\"xyz\"");
        assertFalse(V.isNotModified(r));
    }

    @Test
    void etagListAndWeakAndStarAllMatch() {
        MockHttpServletRequest list = req();
        list.addHeader("If-None-Match", "\"x\", \"abc\"");
        assertTrue(V.isNotModified(list));

        MockHttpServletRequest weak = req();
        weak.addHeader("If-None-Match", "W/\"abc\"");
        assertTrue(V.isNotModified(weak));

        MockHttpServletRequest star = req();
        star.addHeader("If-None-Match", "*");
        assertTrue(V.isNotModified(star));
    }

    @Test
    void noConditionalHeadersMeansModified() {
        assertFalse(V.isNotModified(req()));
    }

    @Test
    void ifModifiedSinceFreshIsNotModifiedStaleIsModified() {
        MockHttpServletRequest fresh = req();
        fresh.addHeader("If-Modified-Since", 2000L); // >= lastModified (1000) at second granularity
        assertTrue(V.isNotModified(fresh));

        MockHttpServletRequest stale = req();
        stale.addHeader("If-Modified-Since", 500L); // < lastModified
        assertFalse(V.isNotModified(stale));
    }

    @Test
    void ifNoneMatchTakesPrecedenceOverIfModifiedSince() {
        // A non-matching ETag forces a fresh render even though the date alone would be fresh.
        MockHttpServletRequest r = req();
        r.addHeader("If-None-Match", "\"xyz\"");
        r.addHeader("If-Modified-Since", 9999L);
        assertFalse(V.isNotModified(r));
    }
}
