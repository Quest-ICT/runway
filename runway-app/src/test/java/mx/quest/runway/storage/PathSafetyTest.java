package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathSafetyTest {

    @Test
    void acceptsSafeIdentifiers() {
        assertTrue(PathSafety.isSafeId("site1"));
        assertTrue(PathSafety.isSafeId("a.b"));
        assertTrue(PathSafety.isSafeId("a-b_c.d"));
        assertTrue(PathSafety.isSafeId("A1"));
    }

    @Test
    void rejectsUnsafeIdentifiers() {
        assertFalse(PathSafety.isSafeId(null));
        assertFalse(PathSafety.isSafeId(""));
        assertFalse(PathSafety.isSafeId("."));
        assertFalse(PathSafety.isSafeId(".."));
        assertFalse(PathSafety.isSafeId("a/b"));
        assertFalse(PathSafety.isSafeId("a\\b"));
        assertFalse(PathSafety.isSafeId("a|b"));
        assertFalse(PathSafety.isSafeId("../x"));
        assertFalse(PathSafety.isSafeId("-leading"));
        assertFalse(PathSafety.isSafeId(".hidden"));
    }

    @Test
    void requireSafeIdReturnsOrThrows() {
        assertEquals("ok", PathSafety.requireSafeId("ok"));
        assertThrows(IllegalArgumentException.class, () -> PathSafety.requireSafeId(".."));
    }

    @Test
    void resolveWithinStaysUnderRoot() {
        Path root = Path.of("/srv/app/sites").toAbsolutePath();
        Path resolved = PathSafety.resolveWithin(root, "acme", "users.yml".replace("/", "_"));
        assertTrue(resolved.startsWith(root.toAbsolutePath().normalize()));
        assertTrue(resolved.endsWith(Path.of("acme", "users.yml".replace("/", "_"))));
    }

    @Test
    void resolveWithinRejectsTraversalSegments() {
        Path root = Path.of("/srv/app/sites");
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolveWithin(root, ".."));
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolveWithin(root, "ok", ".."));
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolveWithin(root, "a/b"));
    }

    @Test
    void compoundKeyJoinsWithPrintableSeparator() {
        assertEquals("a|b|c", PathSafety.compoundKey("a", "b", "c"));
        assertEquals("solo", PathSafety.compoundKey("solo"));
    }

    @Test
    void compoundKeyRejectsSeparatorInPartAndNull() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.compoundKey("a|x", "b"));
        assertThrows(NullPointerException.class, () -> PathSafety.compoundKey("a", null));
    }
}
