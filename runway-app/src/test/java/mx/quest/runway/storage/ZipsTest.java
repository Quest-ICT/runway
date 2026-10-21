package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The single source of truth for per-entry decompression caps — the zip-bomb guard shared by restore and import. */
class ZipsTest {

    @Test
    void readBoundedReturnsTheBytesWhenUnderTheCap() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data, Zips.readBounded(new ByteArrayInputStream(data), 1024));
    }

    @Test
    void readBoundedAcceptsExactlyTheCap() throws IOException {
        assertEquals(1024, Zips.readBounded(new ByteArrayInputStream(new byte[1024]), 1024).length,
                "the cap is inclusive — exactly maxBytes is allowed");
    }

    @Test
    void readBoundedRejectsAStreamOverTheCap() {
        IOException ex = assertThrows(IOException.class,
                () -> Zips.readBounded(new ByteArrayInputStream(new byte[2048]), 1024));
        assertTrue(ex.getMessage().contains("per-entry limit"), "the message names the cap it tripped");
    }

    @Test
    void copyBoundedWritesUnderTheCapAndReportsTheCount() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(3, Zips.copyBounded(new ByteArrayInputStream(new byte[3]), out, 1024));
        assertEquals(3, out.size(), "the bytes reached the destination");
    }

    @Test
    void copyBoundedRejectsAnOversizeEntryWithoutWritingTheOverflow() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IOException.class,
                () -> Zips.copyBounded(new ByteArrayInputStream(new byte[2048]), out, 1024));
        assertTrue(out.size() <= 1024, "the overflowing chunk is never written past the cap");
    }
}
