package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mx.quest.runway.endpoint.EndpointDispatchFilter.BodyTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The pre-authentication body bound: exactly the cap is delivered, the next byte is refused. */
class BoundedBodyRequestTest {

    private static BoundedBodyRequest bounded(int bodyBytes, long cap) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        request.setContent(new byte[bodyBytes]);
        return new BoundedBodyRequest(request, cap);
    }

    @Test
    void exactlyTheCapIsDeliveredWhole() throws IOException {
        BoundedBodyRequest request = bounded(100, 100);
        assertEquals(100, request.getInputStream().readAllBytes().length);
        assertFalse(request.exceeded());
    }

    @Test
    void oneByteOverTheCapIsRefusedAndRemembered() {
        BoundedBodyRequest request = bounded(101, 100);
        assertThrows(BodyTooLargeException.class, () -> request.getInputStream().readAllBytes());
        assertTrue(request.exceeded());
        assertThrows(BodyTooLargeException.class, () -> request.getInputStream().read(), "and stays refused");
    }

    @Test
    void singleByteReadsCountToo() throws IOException {
        BoundedBodyRequest request = bounded(3, 2);
        assertEquals(0, request.getInputStream().read());
        assertEquals(0, request.getInputStream().read());
        assertThrows(BodyTooLargeException.class, () -> request.getInputStream().read());
    }

    @Test
    void theReaderIsBoundedAndTheStreamIsOnePerRequest() throws IOException {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/x");
        raw.setContent("héllo".getBytes(StandardCharsets.UTF_8));
        BoundedBodyRequest request = new BoundedBodyRequest(raw, 100);
        assertSame(request.getInputStream(), request.getInputStream());
        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), request.getInputStream().readAllBytes());

        BoundedBodyRequest small = bounded(10, 4);
        assertThrows(BodyTooLargeException.class, () -> small.getReader().read(new char[64]));
    }

    @Test
    void aZeroCapRefusesAnyByteAndANegativeCapIsZero() throws IOException {
        assertEquals(0, bounded(0, 0).getInputStream().readAllBytes().length, "an empty body passes");
        assertThrows(BodyTooLargeException.class, () -> bounded(1, -5).getInputStream().readAllBytes());
    }
}
