package mx.quest.runway.spi.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A plugin's effective per-site settings, as {@link mx.quest.runway.spi.service.PluginSettings#site(String)}
 * builds them: the descriptor's {@code siteSettings:} defaults overlaid by the values stored for the site, plus any
 * preserved keys the schema does not declare. Secret-typed keys are never present (read them with
 * {@link mx.quest.runway.spi.service.PluginSettings#secret(String, String)}).
 *
 * <p><b>Immutable and deep-frozen.</b> The compact constructor copies {@code values}, drops {@code null} keys and
 * values, and freezes nested {@link List}s and {@link Map}s recursively (their {@code null} elements dropped too),
 * so a view can be cached and shared between threads, and nothing a caller does to it — or to the map it was built
 * from — is visible to anyone else.
 *
 * <p><b>Precedence</b> for a value: the stored value, else the descriptor's {@code default}, else the
 * {@code fallback} argument of the accessor. The host applies the first two when it builds the view, so the
 * code fallback only matters for keys the schema does not declare (or declares without a default). Every
 * accessor is <b>lenient</b>: a value that does not parse as the requested type yields the fallback, never an
 * exception — a hand-edited settings file must not break a public page.
 *
 * <p>Nested structures (a list of maps, for example) are not interpreted here; read them from {@link #values()}
 * and parse them in the plugin.
 *
 * @param values the effective key → value map (never {@code null} after construction; {@code null} = empty)
 * @since SPI 0.4.0
 */
public record SettingsView(Map<String, Object> values) {

    public SettingsView {
        values = freezeMap(values);
    }

    /** An empty view: every accessor answers its fallback. */
    public static SettingsView empty() {
        return new SettingsView(Map.of());
    }

    /**
     * The value as a string, or {@code fallback} when the key is absent or its value is blank. A list value is
     * joined with {@code ", "}.
     */
    public String string(String key, String fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList())
                : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    /**
     * The value as a boolean: a {@link Boolean} as is; the strings {@code true}/{@code on}/{@code yes}/{@code 1}
     * as {@code true} and {@code false}/{@code off}/{@code no}/{@code 0} as {@code false} (case-insensitive,
     * trimmed); a number as {@code != 0}. Anything else — absent, blank, unrecognised — yields {@code fallback}.
     */
    public boolean bool(String key, boolean fallback) {
        Object value = values.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> fallback;
        };
    }

    /**
     * The value as a whole number: an integral {@link Number}, or a string that parses as a {@code long}
     * (trimmed). A fractional number, an unparsable string, or an absent key yields {@code fallback}.
     */
    public long integer(String key, long fallback) {
        Object value = values.get(key);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException notIntegral) {
                return fallback;
            }
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    /**
     * As {@link #integer(String, long)}, then clamped into {@code [min, max]} — the fallback included, so the
     * result is always in range.
     *
     * @throws IllegalArgumentException if {@code min > max}
     */
    public long integer(String key, long fallback, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " > max " + max);
        }
        return Math.max(min, Math.min(max, integer(key, fallback)));
    }

    /**
     * The value as a decimal: a {@link Number} (converted through its string form, so {@code 1.1} stays
     * {@code 1.1}) or a string that parses as a {@link BigDecimal}; otherwise {@code fallback} (which may be
     * {@code null}).
     */
    public BigDecimal decimal(String key, BigDecimal fallback) {
        Object value = values.get(key);
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    /**
     * The value as a list of non-blank, trimmed strings: a YAML list element by element, or a string split on
     * commas and newlines. An absent key yields an empty list. Never {@code null}; the list is unmodifiable.
     */
    public List<String> list(String key) {
        Object value = values.get(key);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        } else if (value != null) {
            for (String token : String.valueOf(value).split("[,\\n]")) {
                String text = token.trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                out.put(String.valueOf(key), freeze(value));
            }
        });
        return Collections.unmodifiableMap(out);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    out.add(freeze(item));
                }
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }
}
