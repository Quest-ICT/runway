package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The global advice publishes {@link CspFilter}'s per-request nonce (the {@link CspFilter#NONCE_REQUEST_ATTRIBUTE}
 * request attribute) as the {@code cspNonce} model attribute, and degrades to {@code ""} when none is present.
 */
class CspNonceAdviceTest {

    private final CspNonceAdvice advice = new CspNonceAdvice();

    @Test
    void exposesTheRequestAttributeNonce() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CspFilter.NONCE_REQUEST_ATTRIBUTE, "Abc123-_xyz");
        assertThat(advice.cspNonce(request)).isEqualTo("Abc123-_xyz");
    }

    @Test
    void returnsEmptyWhenNoNoncePresent() {
        // CSP OFF, or a path the filter skipped — a template binding must render "" not the literal null.
        assertThat(advice.cspNonce(new MockHttpServletRequest())).isEmpty();
    }
}
