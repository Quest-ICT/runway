package mx.quest.runway.plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code requires:} entry — a hard dependency gate. Three forms only, the operator a single
 * space apart: {@code id} (any version), {@code id >= v} (minimum — API-stable deps), {@code id == v} (exact —
 * compiled-against binaries). <b>Anything else fails at parse</b>: an unknown operator ({@code >}, {@code ~>}),
 * a double space, or a malformed form is a loud descriptor error, never a silent fallback to "require a plugin
 * literally named '{@code id > v}'".
 *
 * @param id      the required plugin id
 * @param op      the comparison
 * @param version the version bound ({@code null} for {@link Op#ANY})
 */
public record PluginRequirement(String id, Op op, PluginVersion version) {

    /** The comparison a requirement applies to the installed version. */
    public enum Op {
        /** Any installed version satisfies (a bare {@code id}). */
        ANY,
        /** The installed version must be {@code >=} the bound. */
        AT_LEAST,
        /** The installed version must equal the bound. */
        EXACT
    }

    private static final Pattern VERSIONED = Pattern.compile("^(\\S+) (>=|==) (\\S+)$");
    private static final Pattern BARE = Pattern.compile("^\\S+$");

    /**
     * Parses a requirement string.
     *
     * @throws IllegalArgumentException if it is not exactly {@code id}, {@code id >= v}, or {@code id == v}
     */
    public static PluginRequirement parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("requirement is null");
        }
        String text = raw.trim();
        if (BARE.matcher(text).matches()) {
            return new PluginRequirement(text, Op.ANY, null);
        }
        Matcher m = VERSIONED.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "malformed requirement '" + raw + "' (expected `id`, `id >= v`, or `id == v`, single-spaced)");
        }
        Op op = m.group(2).equals(">=") ? Op.AT_LEAST : Op.EXACT;
        return new PluginRequirement(m.group(1), op, PluginVersion.parse(m.group(3)));
    }

    /** Whether {@code installed} (the version actually present) satisfies this requirement. */
    public boolean satisfiedBy(PluginVersion installed) {
        return switch (op) {
            case ANY -> true;
            case AT_LEAST -> installed != null && installed.compareTo(version) >= 0;
            case EXACT -> installed != null && installed.compareTo(version) == 0;
        };
    }
}
