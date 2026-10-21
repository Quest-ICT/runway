package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilitiesTest {

    @Test
    void noneIsEmpty() {
        assertTrue(Capabilities.none().isEmpty());
        assertFalse(Capabilities.none().has("anything"));
    }

    @Test
    void ofHoldsFlags() {
        Capabilities c = Capabilities.of("a", "b");
        assertTrue(c.has("a"));
        assertTrue(c.has("b"));
        assertFalse(c.has("c"));
        assertEquals(2, c.flags().size());
    }

    @Test
    void ofWithNoArgsIsNoneInstance() {
        assertSame(Capabilities.none(), Capabilities.of());
    }

    @Test
    void ofDeduplicates() {
        assertEquals(1, Capabilities.of("a", "a").flags().size());
    }

    @Test
    void flagsAreUnmodifiable() {
        Capabilities c = Capabilities.of("a");
        assertThrows(UnsupportedOperationException.class, () -> c.flags().add("b"));
    }

    @Test
    void constructorDefensivelyCopies() {
        Set<String> source = new HashSet<>(Set.of("a"));
        Capabilities c = new Capabilities(source);
        source.add("b");
        assertFalse(c.has("b"));
        assertEquals(1, c.flags().size());
    }

    @Test
    void nullSetNormalizesToEmpty() {
        assertTrue(new Capabilities(null).isEmpty());
    }

    @Test
    void nullFlagIsRejected() {
        assertThrows(NullPointerException.class, () -> Capabilities.of("a", null));
    }

    @Test
    void hasNullIsFalse() {
        assertFalse(Capabilities.of("a").has(null));
    }

    @Test
    void withAddsWithoutMutatingOriginal() {
        Capabilities a = Capabilities.of("a");
        Capabilities b = a.with("b");
        assertTrue(b.has("a"));
        assertTrue(b.has("b"));
        assertFalse(a.has("b"));
    }

    @Test
    void withNothingReturnsSameInstance() {
        Capabilities a = Capabilities.of("a");
        assertSame(a, a.with());
    }

    @Test
    void equalityIsOrderIndependent() {
        assertEquals(Capabilities.of("a", "b"), Capabilities.of("b", "a"));
        assertEquals(Capabilities.of("a", "b").hashCode(), Capabilities.of("b", "a").hashCode());
    }
}
