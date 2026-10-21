package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Strict MAJOR.MINOR.PATCH: integer segment-wise compare, missing trailing = 0, non-numeric throws at parse. */
class PluginVersionTest {

    @Test
    void missingTrailingSegmentsPadWithZero() {
        assertEquals(PluginVersion.parse("2.1.0"), PluginVersion.parse("2.1"));
        assertEquals(PluginVersion.parse("3.0.0"), PluginVersion.parse("3"));
        assertEquals("2.1.0", PluginVersion.parse("2.1").toString());
    }

    @Test
    void segmentsCompareAsIntegersNotLexically() {
        assertTrue(PluginVersion.parse("2.10").compareTo(PluginVersion.parse("2.9")) > 0, "10 > 9 numerically");
        assertTrue(PluginVersion.parse("1.0.0").compareTo(PluginVersion.parse("1.0.1")) < 0);
        assertEquals(0, PluginVersion.parse("1.2.3").compareTo(PluginVersion.parse("1.2.3")));
    }

    @Test
    void nonNumericOrMalformedSegmentsThrowAtParse() {
        for (String bad : new String[] {"2.1-RC1", "2.1.x", "v2", "", "  ", "2.1.0.0", "2..1", "-1.0"}) {
            assertThrows(IllegalArgumentException.class, () -> PluginVersion.parse(bad), "should reject '" + bad + "'");
        }
    }
}
