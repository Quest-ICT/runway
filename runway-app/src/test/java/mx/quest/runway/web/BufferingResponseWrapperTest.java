package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Capture, overflow, and the Content-Length-withholding contract of the buffering wrapper. */
class BufferingResponseWrapperTest {

    @Test
    void capturesOutputStreamWritesWithoutTouchingTheRealResponse() throws Exception {
        MockHttpServletResponse real = new MockHttpServletResponse();
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(real, 1024);

        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        wrapper.getOutputStream().write(payload);
        wrapper.flushBuffer();

        assertFalse(wrapper.isOverflowed());
        assertArrayEquals(payload, wrapper.capturedBytes());
        assertEquals(0, real.getContentAsByteArray().length, "nothing should reach the real response while capturing");
    }

    @Test
    void capturesWriterWritesUsingTheResponseCharset() throws Exception {
        MockHttpServletResponse real = new MockHttpServletResponse();
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(real, 1024);
        wrapper.setContentType("text/html;charset=UTF-8");

        wrapper.getWriter().write("héllo"); // héllo
        wrapper.flushBuffer();

        assertFalse(wrapper.isOverflowed());
        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), wrapper.capturedBytes());
    }

    @Test
    void overflowStreamsThroughAndFlagsOverflow() throws Exception {
        MockHttpServletResponse real = new MockHttpServletResponse();
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(real, 4); // tiny cap

        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
        wrapper.getOutputStream().write(payload);
        wrapper.flushBuffer();

        assertTrue(wrapper.isOverflowed());
        assertArrayEquals(payload, real.getContentAsByteArray(), "all bytes should have streamed to the real response");
    }

    @Test
    void contentLengthIsWithheldButOtherHeadersPassThrough() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(real, 1024);

        wrapper.setContentLength(999);
        wrapper.setHeader("Content-Length", "999");
        wrapper.setIntHeader("Content-Length", 999);
        wrapper.setHeader("X-Test", "kept");

        assertNotEquals(999, real.getContentLength(), "setContentLength must not propagate");
        assertNull(real.getHeader("Content-Length"), "a Content-Length header must not propagate");
        assertEquals("kept", real.getHeader("X-Test"), "unrelated headers must pass through");
    }

    @Test
    void sendErrorPassesThroughDiscardsTheCaptureAndIsReported() throws Exception {
        MockHttpServletResponse real = new MockHttpServletResponse();
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(real, 1024);
        wrapper.getWriter().write("half a page");
        assertFalse(wrapper.isErrorSent());

        wrapper.sendError(404, "gone");

        assertTrue(wrapper.isErrorSent(), "the caller learns the container owns the response");
        assertEquals(404, real.getStatus(), "the status reaches the real response");
        assertEquals("gone", real.getErrorMessage());
        assertEquals(0, wrapper.capturedBytes().length, "the unsent body is discarded, as sendError does");
    }

    @Test
    void sendErrorWithoutAMessageIsReportedToo() throws Exception {
        MockHttpServletResponse real = new MockHttpServletResponse();
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(real, 1024);

        wrapper.sendError(500);

        assertTrue(wrapper.isErrorSent());
        assertEquals(500, real.getStatus());
    }
}
