package mx.quest.runway.plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The typed coercion + validation for a declared plugin-setting type — shared by the app-level
 * ({@link PluginSettingsService}) and per-tenant ({@code SitePluginConfigService}) settings engines so the two
 * never drift. Lenient: an unknown type behaves as {@code string}. {@link #coerce} validates a <b>resolved</b>
 * value (the caller applies its own blank policy — fall back to the default vs. remove the key — before
 * calling), returning the stored Java value or {@code null} for a blank non-text value; a {@code secret} is
 * handled by the caller (encryption), never here.
 */
public enum SettingType {
    STRING, TEXT, SECRET, INT, DECIMAL, BOOLEAN, ENUM, LIST;

    /** The type for a raw declaration string (null/unknown → {@link #STRING}). */
    public static SettingType of(String raw) {
        if (raw == null) {
            return STRING;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return STRING;
        }
    }

    /**
     * Coerces an already-resolved {@code value} to its stored form, validating against the def's range.
     *
     * @return the typed value, or {@code null} when {@code value} is blank (and not free-text)
     * @throws SettingValidationException on a value that fails the type/range (carries the setting key)
     */
    public Object coerce(PluginSettingDef def, String value) {
        if (value == null || (value.isBlank() && this != TEXT)) {
            return null;
        }
        return switch (this) {
            case STRING, ENUM -> coerceScalar(def, value.trim());
            case TEXT -> value;
            case INT -> coerceInt(def, value.trim());
            case DECIMAL -> coerceDecimal(def, value.trim());
            case BOOLEAN -> coerceBoolean(value.trim());
            case LIST -> coerceList(value);
            case SECRET -> value; // the caller encrypts; coerce never stores a raw secret
        };
    }

    private Object coerceScalar(PluginSettingDef def, String value) {
        if (this == ENUM) {
            List<String> allowed = splitRange(def.range());
            if (!allowed.isEmpty() && !allowed.contains(value)) {
                throw new SettingValidationException(def.key(), "'" + value + "' is not one of " + allowed,
                        "op.config.invalid.notOneOf", value, String.join(", ", allowed));
            }
        }
        return value;
    }

    private Object coerceInt(PluginSettingDef def, String value) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException bad) {
            throw new SettingValidationException(def.key(), "'" + value + "' is not a whole number",
                    "op.config.invalid.notWhole", value);
        }
        long[] bounds = numericRange(def.range());
        if (parsed < bounds[0] || parsed > bounds[1]) {
            String range = bounds[0] + ".." + bounds[1];
            throw new SettingValidationException(def.key(), value + " is outside the allowed range " + range,
                    "op.config.invalid.outOfRange", value, range);
        }
        return parsed;
    }

    private Object coerceDecimal(PluginSettingDef def, String value) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value);
        } catch (NumberFormatException bad) {
            throw new SettingValidationException(def.key(), "'" + value + "' is not a decimal number",
                    "op.config.invalid.notDecimal", value);
        }
        // A declared range bounds a decimal as it bounds an int. Until 1.0.0 it was shown on the form but not
        // enforced, so protection's refill rate accepted 0, which throttles an address for good.
        String range = def.range();
        if (range != null && range.contains("..")) {
            String[] parts = range.split("\\.\\.", 2);
            BigDecimal min;
            BigDecimal max;
            try {
                min = parts[0].isBlank() ? null : new BigDecimal(parts[0].trim());
                max = parts.length < 2 || parts[1].isBlank() ? null : new BigDecimal(parts[1].trim());
            } catch (NumberFormatException malformedRange) {
                return parsed; // the descriptor's range is not numeric: nothing to enforce (never a 500 on save)
            }
            if ((min != null && parsed.compareTo(min) < 0) || (max != null && parsed.compareTo(max) > 0)) {
                throw new SettingValidationException(def.key(), value + " is outside the allowed range " + range,
                        "op.config.invalid.outOfRange", value, range);
            }
        }
        return parsed;
    }

    private Object coerceBoolean(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")
                || value.equals("1") || value.equalsIgnoreCase("yes");
    }

    private Object coerceList(String value) {
        List<String> items = new ArrayList<>();
        for (String token : value.split("[,\\n]")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /**
     * The allowed values of an {@code enum} range, in declared order — the one parse both the validator and the
     * settings form's select use, so the choices offered can never differ from the choices accepted.
     */
    public static List<String> allowedValues(String range) {
        return splitRange(range);
    }

    private static List<String> splitRange(String range) {
        if (range == null || range.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String token : range.split("[,|]")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /** Parses {@code "min..max"} / {@code "min.."} / {@code "..max"}; defaults to the full long range. */
    private static long[] numericRange(String range) {
        long min = Long.MIN_VALUE;
        long max = Long.MAX_VALUE;
        if (range != null && range.contains("..")) {
            String[] parts = range.split("\\.\\.", 2);
            min = parseBound(parts[0], min);
            max = parts.length > 1 ? parseBound(parts[1], max) : max;
        }
        return new long[] {min, max};
    }

    private static long parseBound(String text, long fallback) {
        try {
            return text == null || text.isBlank() ? fallback : Long.parseLong(text.trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }
}
