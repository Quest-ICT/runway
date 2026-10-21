package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link SettingsView}: deep freezing, null normalisation, and the lenient typed accessors. */
class SettingsViewTest {

    private static SettingsView of(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new SettingsView(map);
    }

    @Test
    void nullsAreDroppedAndNestedValuesAreDeepFrozen() {
        List<Object> inner = new ArrayList<>(Arrays.asList("a", null, "b"));
        Map<String, Object> nested = new HashMap<>();
        nested.put("k", "v");
        nested.put("gone", null);
        nested.put("list", inner);
        Map<String, Object> source = new HashMap<>();
        source.put("x", nested);
        source.put("dropped", null);
        source.put(null, "no key");

        SettingsView view = new SettingsView(source);
        source.put("late", "added after");
        inner.add("c");

        assertEquals(1, view.values().size(), view.values().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) view.values().get("x");
        assertEquals(Map.of("k", "v", "list", List.of("a", "b")), frozen,
                "null entries dropped at every level; later edits to the source are invisible");
        assertThrows(UnsupportedOperationException.class, () -> view.values().put("y", 1));
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("y", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) frozen.get("list")).clear());
    }

    @Test
    void aNullMapIsEmpty() {
        assertTrue(new SettingsView(null).values().isEmpty());
        assertTrue(SettingsView.empty().values().isEmpty());
    }

    @Test
    void stringUsesTheFallbackForAbsentAndBlankValuesAndJoinsLists() {
        SettingsView view = of("name", "Acme", "blank", "  ", "n", 5, "tags", List.of("a", "b"));
        assertEquals("Acme", view.string("name", "x"));
        assertEquals("x", view.string("blank", "x"));
        assertEquals("x", view.string("absent", "x"));
        assertNull(view.string("absent", null));
        assertEquals("5", view.string("n", "x"));
        assertEquals("a, b", view.string("tags", "x"));
    }

    @Test
    void boolAcceptsBooleansWordsAndNumbersAndFallsBackOnAnythingElse() {
        SettingsView view = of("t", true, "f", false, "on", "ON", "yes", " yes ", "one", "1", "off", "off",
                "no", "No", "zero", "0", "num", 2, "numZero", 0, "junk", "maybe", "blank", "");
        assertTrue(view.bool("t", false));
        assertFalse(view.bool("f", true));
        assertTrue(view.bool("on", false));
        assertTrue(view.bool("yes", false));
        assertTrue(view.bool("one", false));
        assertFalse(view.bool("off", true));
        assertFalse(view.bool("no", true));
        assertFalse(view.bool("zero", true));
        assertTrue(view.bool("num", false));
        assertFalse(view.bool("numZero", true));
        assertTrue(view.bool("junk", true), "unrecognised → fallback");
        assertFalse(view.bool("junk", false));
        assertTrue(view.bool("blank", true));
        assertTrue(view.bool("absent", true));
    }

    @Test
    void integerParsesIntegralValuesOnlyAndClamps() {
        SettingsView view = of("i", 42, "l", 7_000_000_000L, "s", " 13 ", "d", 2.0, "frac", 2.5, "junk", "x",
                "big", new BigDecimal("12"));
        assertEquals(42, view.integer("i", -1));
        assertEquals(7_000_000_000L, view.integer("l", -1));
        assertEquals(13, view.integer("s", -1));
        assertEquals(2, view.integer("d", -1), "an integral double is accepted");
        assertEquals(-1, view.integer("frac", -1), "a fractional number is not a whole number");
        assertEquals(-1, view.integer("junk", -1));
        assertEquals(12, view.integer("big", -1));
        assertEquals(-1, view.integer("absent", -1));
        assertEquals(10, view.integer("i", 0, 1, 10), "clamped to max");
        assertEquals(1, view.integer("absent", -5, 1, 10), "the fallback is clamped too");
        assertThrows(IllegalArgumentException.class, () -> view.integer("i", 0, 10, 1));
    }

    @Test
    void decimalParsesNumbersExactlyAndFallsBack() {
        SettingsView view = of("d", 1.1, "s", "2.50", "bd", new BigDecimal("3.3"), "junk", "x");
        assertEquals(new BigDecimal("1.1"), view.decimal("d", null));
        assertEquals(new BigDecimal("2.50"), view.decimal("s", null));
        assertEquals(new BigDecimal("3.3"), view.decimal("bd", null));
        assertEquals(BigDecimal.ONE, view.decimal("junk", BigDecimal.ONE));
        assertNull(view.decimal("absent", null));
    }

    @Test
    void listReadsYamlListsAndSeparatedStrings() {
        SettingsView view = of("yaml", List.of(" a ", "", "b"), "csv", "x, y,,z", "lines", "p\nq", "one", "solo");
        assertEquals(List.of("a", "b"), view.list("yaml"));
        assertEquals(List.of("x", "y", "z"), view.list("csv"));
        assertEquals(List.of("p", "q"), view.list("lines"));
        assertEquals(List.of("solo"), view.list("one"));
        assertEquals(List.of(), view.list("absent"));
        assertThrows(UnsupportedOperationException.class, () -> view.list("yaml").add("c"));
    }

    @Test
    void pluginInfoRequiresEveryComponent() {
        assertEquals("blog", new PluginInfo("blog", "1.0.0", Path.of("plugins", "blog")).id());
        assertThrows(NullPointerException.class, () -> new PluginInfo(null, "1.0.0", Path.of("x")));
        assertThrows(NullPointerException.class, () -> new PluginInfo("a", null, Path.of("x")));
        assertThrows(NullPointerException.class, () -> new PluginInfo("a", "1.0.0", null));
    }
}
