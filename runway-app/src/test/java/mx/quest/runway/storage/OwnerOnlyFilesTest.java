package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The owner-only permission helper: on a POSIX filesystem it applies rw------- to a secret-bearing file. */
class OwnerOnlyFilesTest {

    @Test
    void restrictsAPosixFileToOwnerReadWrite(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("secret.yml");
        Files.writeString(file, "token: s3cr3t\n");
        // Prove the starting perms are broader than owner-only, so the assertion is meaningful.
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException nonPosix) {
            Assumptions.abort("not a POSIX filesystem — the ACL path is exercised on Windows only");
        }

        OwnerOnlyFiles.restrictToOwner(file);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                "a secret-bearing file is restricted to its owner");
    }
}
