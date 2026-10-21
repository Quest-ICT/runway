package mx.quest.runway.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The reject seam: pass reaches the chain; a reject short-circuits with a tiny Connection:close response. */
class EdgeRejectFilterTest {

    private static MockHttpServletResponse run(EdgeGuard guard, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new EdgeRejectFilter(guard, new EdgeRejectionRecorder(null, false, 60)).doFilter(request, response, chain);
        return response;
    }

    @Test
    void aPassVerdictReachesTheChain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(request -> EdgeVerdict.pass(), chain);
        assertNotNull(chain.getRequest(), "the chain must be invoked on a pass");
        assertEquals(200, response.getStatus());
    }

    @Test
    void aRejectShortCircuitsWithoutTheChain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(request -> EdgeVerdict.reject(403), chain);
        assertEquals(403, response.getStatus());
        assertEquals("close", response.getHeader("Connection"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Retry-After"), "no Retry-After when retryAfterSeconds is 0");
        assertTrue(response.getContentType().startsWith("text/plain"));
        assertEquals("Forbidden.\n", response.getContentAsString());
        assertEquals(response.getContentAsByteArray().length, response.getContentLength());
        assertNull(chain.getRequest(), "the chain must NOT be reached on a reject");
    }

    @Test
    void aRateLimitRejectCarriesRetryAfterAndItsMessage() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(request -> EdgeVerdict.reject(429, 7), chain);
        assertEquals(429, response.getStatus());
        assertEquals("7", response.getHeader("Retry-After"));
        assertEquals("Too Many Requests.\n", response.getContentAsString());
        assertNull(chain.getRequest());
    }

    @Test
    void anUnmappedStatusGetsTheGenericMessageAndNoRetryAfter() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(request -> EdgeVerdict.reject(503), chain);
        assertEquals(503, response.getStatus());
        assertEquals("Request rejected.\n", response.getContentAsString());
        assertNull(response.getHeader("Retry-After"));
        assertNull(chain.getRequest());
    }

    @Test
    void theGatedRequestFactsAreHandedToTheGuard() throws Exception {
        AtomicReference<EdgeRequest> seen = new AtomicReference<>();
        run(request -> {
            seen.set(request);
            return EdgeVerdict.pass();
        }, new MockFilterChain());
        assertEquals("203.0.113.9", seen.get().clientIp());
        assertEquals("GET", seen.get().method());
        assertEquals("/x", seen.get().path());
    }
}
