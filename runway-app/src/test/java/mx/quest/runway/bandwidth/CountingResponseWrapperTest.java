package mx.quest.runway.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** The counting wrapper tallies exactly the bytes written, through both the stream and the writer. */
class CountingResponseWrapperTest {

    @Test
    void countsBytesWrittenToTheOutputStream() throws Exception {
        CountingResponseWrapper wrapper = new CountingResponseWrapper(new MockHttpServletResponse());
        wrapper.getOutputStream().write("hello world".getBytes(StandardCharsets.UTF_8)); // 11 bytes
        wrapper.getOutputStream().write('!');
        assertEquals(12, wrapper.bytesWritten());
    }

    @Test
    void countsBytesWrittenThroughTheWriter() throws Exception {
        CountingResponseWrapper wrapper = new CountingResponseWrapper(new MockHttpServletResponse());
        wrapper.getWriter().write("abcde"); // 5 ASCII bytes
        wrapper.flushBuffer();
        assertEquals(5, wrapper.bytesWritten());
    }
}
