package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** A participant can neither write nor look up an entry outside its own namespace. */
class ZipBackupEntryNameTest {

    private static final List<String> UNSAFE = List.of("", " ", "../users.yml", "a/../../b", "/etc/passwd",
            "a\\b", "C:evil", "a//b", "./a", "a/.", "dir/", "nul\0byte");

    @Test
    void plainRelativeNamesAreAccepted() {
        for (String ok : List.of("notes.yml", "a/b/c.txt", "x.y.z", "..hidden-but-not-dotdot", "a-b_c")) {
            assertEquals(ok, ZipBackup.requireSafeEntry(ok));
        }
    }

    @Test
    void unsafeNamesAreRefusedOnWrite() throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new ByteArrayOutputStream())) {
            ZipBackup.Writer writer = new ZipBackup.Writer(zip, "demo");
            for (String bad : UNSAFE) {
                assertThrows(IllegalArgumentException.class, () -> writer.put(bad), "must refuse '" + bad + "'");
            }
            assertThrows(IllegalArgumentException.class, () -> writer.put(null));
            try (OutputStream out = writer.put("fine.txt")) {
                out.write(1);
            }
        }
    }

    @Test
    void unsafeNamesAreRefusedOnRead() throws Exception {
        ZipBackup.Reader reader = new ZipBackup.Reader(Map.of("fine.txt", new byte[] {1}));
        for (String bad : UNSAFE) {
            assertThrows(IllegalArgumentException.class, () -> reader.has(bad), "has('" + bad + "')");
            assertThrows(IllegalArgumentException.class, () -> reader.open(bad), "open('" + bad + "')");
        }
        assertTrue(reader.has("fine.txt"));
        assertFalse(reader.has("other.txt"));
    }
}
