package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The gate: forwarded headers are honored only from a trusted peer, and hidden (raw peer stands) otherwise. */
class TrustedForwardedHeaderFilterTest {

    private static HttpServletRequest run(TrustedForwardedHeaderFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private static MockHttpServletRequest behindProxy(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-Host", "example.com");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        return request;
    }

    @Test
    void aTrustedPeersForwardedHeadersAreHonoredAndStripped() throws Exception {
        TrustedForwardedHeaderFilter filter = new TrustedForwardedHeaderFilter(CidrSet.of(List.of("10.0.0.0/8")), true);
        HttpServletRequest seen = run(filter, behindProxy("10.0.0.9"));
        assertEquals("example.com", seen.getServerName(), "host rewritten from X-Forwarded-Host");
        assertEquals("https", seen.getScheme(), "scheme rewritten from X-Forwarded-Proto");
        assertTrue(seen.isSecure());
        assertEquals("203.0.113.7", seen.getRemoteAddr(), "remote addr rewritten from X-Forwarded-For");
        assertNull(seen.getHeader("X-Forwarded-For"), "forwarded headers are consumed/stripped");
        assertNull(seen.getHeader("X-Forwarded-Host"));
    }

    @Test
    void anUntrustedPeersForwardedHeadersAreHiddenAndTheRawPeerStands() throws Exception {
        TrustedForwardedHeaderFilter filter = new TrustedForwardedHeaderFilter(CidrSet.of(List.of("10.0.0.0/8")), true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("203.0.113.7"); // not in 10.0.0.0/8
        request.addHeader("X-Forwarded-For", "10.0.0.1");
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "8443");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        HttpServletRequest seen = (HttpServletRequest) chain.getRequest();

        assertNull(seen.getHeader("X-Forwarded-For"), "hidden, not honored");
        assertNull(seen.getHeader("X-Forwarded-Host"));
        // getIntHeader/getDateHeader bypass the getHeader override in HttpServletRequestWrapper — they must
        // be hidden too, or a spoofed X-Forwarded-Port leaks through on the untrusted path.
        assertEquals(-1, seen.getIntHeader("X-Forwarded-Port"), "spoofed port must NOT leak via getIntHeader");
        assertEquals(-1L, seen.getDateHeader("X-Forwarded-For"), "forwarded headers must be hidden via getDateHeader too");
        // The forwarded names must also be absent from the enumerated header names.
        boolean anyForwardedListed = java.util.Collections.list(seen.getHeaderNames()).stream()
                .anyMatch(n -> n.toLowerCase().startsWith("x-forwarded") || n.equalsIgnoreCase("forwarded"));
        assertFalse(anyForwardedListed, "no forwarded header should appear in getHeaderNames()");
        assertEquals("203.0.113.7", seen.getRemoteAddr(), "the raw connection address stands");
        assertEquals("localhost", seen.getServerName(), "host NOT rewritten from a spoofable header");
        assertFalse(seen.isSecure());
    }

    @Test
    void anEmptyTrustSetIgnoresForwardedHeadersEvenFromLoopback() throws Exception {
        TrustedForwardedHeaderFilter filter = new TrustedForwardedHeaderFilter(CidrSet.of(List.of()), true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        HttpServletRequest seen = run(filter, request);
        assertNull(seen.getHeader("X-Forwarded-For"), "empty trust set = trust nothing, even loopback");
        assertEquals("127.0.0.1", seen.getRemoteAddr());
    }

    @Test
    void disabledHidesForwardedHeadersRegardlessOfTrust() throws Exception {
        TrustedForwardedHeaderFilter filter = new TrustedForwardedHeaderFilter(CidrSet.of(List.of("0.0.0.0/0")), false);
        HttpServletRequest seen = run(filter, behindProxy("10.0.0.9"));
        assertNull(seen.getHeader("X-Forwarded-Host"), "forwarded handling off => always hide");
        assertEquals("localhost", seen.getServerName());
    }
}
