package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

    @Test
    void writesBytes(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("a.txt");
        AtomicFiles.write(target, "hello".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    }

    @Test
    void writesViaContentWriter(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("b.txt");
        AtomicFiles.write(target, out -> out.write("streamed".getBytes(StandardCharsets.UTF_8)));
        assertEquals("streamed", Files.readString(target));
    }

    @Test
    void overwritesExistingFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("c.txt");
        AtomicFiles.write(target, "first".getBytes(StandardCharsets.UTF_8));
        AtomicFiles.write(target, "second".getBytes(StandardCharsets.UTF_8));
        assertEquals("second", Files.readString(target));
    }

    @Test
    void createsParentDirectories(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested/deep/d.txt");
        AtomicFiles.write(target, "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void failedWriteLeavesOriginalIntactAndNoTempFiles(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("e.txt");
        AtomicFiles.write(target, "original".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> AtomicFiles.write(target, out -> {
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("boom");
        }));

        assertEquals("original", Files.readString(target), "original content must survive a failed write");
        try (Stream<Path> entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "no temp files should be left behind");
        }
    }

    @Test
    void noTempLeftAfterSuccess(@TempDir Path dir) throws IOException {
        AtomicFiles.write(dir.resolve("f.txt"), "ok".getBytes(StandardCharsets.UTF_8));
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> tmp = entries.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(tmp.isEmpty());
        }
    }
}
