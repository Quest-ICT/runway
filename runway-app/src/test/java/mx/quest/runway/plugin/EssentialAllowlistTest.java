package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Only allowlisted ids are essential; a feature plugin cannot self-promote. */
class EssentialAllowlistTest {

    @Test
    void onlyTheCodeDefinedSecurityFloorPluginsAreEssential() {
        assertTrue(EssentialAllowlist.contains("protection"), "the protection plugin is the security floor");
        assertFalse(EssentialAllowlist.contains("contact"), "a feature plugin is never essential");
        assertFalse(EssentialAllowlist.contains("anything-self-marked"));
    }
}
