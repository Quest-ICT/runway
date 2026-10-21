package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Only `id`, `id >= v`, `id == v` parse; anything else fails loudly (no raw-string fallback). */
class PluginRequirementTest {

    @Test
    void bareIdRequiresAnyVersion() {
        PluginRequirement r = PluginRequirement.parse("mail");
        assertEquals("mail", r.id());
        assertEquals(PluginRequirement.Op.ANY, r.op());
        assertTrue(r.satisfiedBy(PluginVersion.parse("0.0.1")));
    }

    @Test
    void atLeastIsAMinimum() {
        PluginRequirement r = PluginRequirement.parse("mail >= 2.1");
        assertEquals("mail", r.id());
        assertEquals(PluginRequirement.Op.AT_LEAST, r.op());
        assertTrue(r.satisfiedBy(PluginVersion.parse("2.1")));
        assertTrue(r.satisfiedBy(PluginVersion.parse("2.2")));
        assertFalse(r.satisfiedBy(PluginVersion.parse("2.0.9")));
    }

    @Test
    void exactIsAnEquality() {
        PluginRequirement r = PluginRequirement.parse("mail == 2.1");
        assertEquals(PluginRequirement.Op.EXACT, r.op());
        assertTrue(r.satisfiedBy(PluginVersion.parse("2.1.0")));
        assertFalse(r.satisfiedBy(PluginVersion.parse("2.1.1")));
    }

    @Test
    void unknownOperatorsAndMalformedFormsThrow() {
        for (String bad : new String[] {"mail > 2.1", "mail ~> 2", "mail < 2", "mail  >= 2.1", "mail >=2.1",
                "mail 2.1", "mail >= ", ">= 2.1", "mail >= 2.1 extra"}) {
            assertThrows(IllegalArgumentException.class, () -> PluginRequirement.parse(bad), "should reject '" + bad + "'");
        }
    }
}
