package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A declared range bounds a decimal setting on save, as it bounds an int. */
class SettingTypeDecimalRangeTest {

    private static Object save(String range, String value) {
        return SettingType.DECIMAL.coerce(new PluginSettingDef("rate", "decimal", null, "Rate", range), value);
    }

    @Test
    void aValueInsideTheRangeIsKept() {
        assertEquals(new BigDecimal("0.01"), save("0.01..1000", "0.01"));
        assertEquals(new BigDecimal("1000"), save("0.01..1000", "1000"));
        assertEquals(new BigDecimal("2.5"), save("0.01..", "2.5"), "an open upper bound");
        assertEquals(new BigDecimal("-3"), save("..0", "-3"), "an open lower bound");
    }

    @Test
    void aValueOutsideTheRangeIsAFieldError() {
        SettingValidationException low = assertThrows(SettingValidationException.class, () -> save("0.01..1000", "0"));
        assertEquals("rate", low.key());
        assertThrows(SettingValidationException.class, () -> save("0.01..1000", "1000.5"));
        assertThrows(SettingValidationException.class, () -> save("0.01..", "-1"));
    }

    @Test
    void noRangeOrANonNumericRangeEnforcesNothing() {
        assertEquals(new BigDecimal("0"), save(null, "0"));
        assertEquals(new BigDecimal("0"), save("a..b", "0"), "a malformed range never turns a save into a 500");
    }
}
