package mx.quest.database.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Every name the database plugin creates or checks: the namespace, the schema names and the plugin prefixes. Pure
 * functions, so the rules that keep installs, sites and plugins apart are tested without a database.
 *
 * <ul>
 *   <li>The Runway-wide schema is {@code <ns>_app}; a site's schema is {@code <ns>_s_<base>_<gen>}, where
 *       {@code <base>} is the site id lower-cased with everything outside {@code [a-z0-9]} mapped to {@code _}, and
 *       {@code <gen>} comes from an install-wide sequence, so a name is never reused even when two site ids map to
 *       the same base.</li>
 *   <li>A plugin's prefix is its id mapped the same way, ending in a double underscore, at most
 *       {@value #PREFIX_MAX} characters (a longer one is cut and given a short hash).</li>
 * </ul>
 */
public final class Names {

    /** The namespace grammar: no underscore, so one install's names never start another's. */
    public static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9]{0,11}");

    /** The longest plugin prefix, double underscore included. */
    public static final int PREFIX_MAX = 24;

    /** Database's own objects; no plugin prefix may start with it. */
    public static final String RESERVED_PREFIX = "rw_";

    private static final Pattern SITE_SCHEMA_TAIL = Pattern.compile("s_[a-z0-9_]+");

    private Names() {
    }

    /** The namespace, checked: refuses anything outside {@link #NAMESPACE}. */
    public static String namespace(String value) {
        if (value == null || !NAMESPACE.matcher(value).matches()) {
            throw new IllegalArgumentException("the namespace must be a lower-case letter followed by up to 11 "
                    + "lower-case letters or digits: " + value);
        }
        return value;
    }

    /** {@code <ns>_app}. */
    public static String appSchema(String namespace) {
        return namespace(namespace) + "_app";
    }

    /**
     * {@code <ns>_s_<base>_<gen>}, with the base cut so that the whole name is at most {@code maxLength} bytes.
     *
     * @param maxLength the engine's identifier limit (63 for PostgreSQL, 64 for MariaDB)
     */
    public static String siteSchema(String namespace, String siteId, long generation, int maxLength) {
        Objects.requireNonNull(siteId, "siteId");
        if (siteId.isEmpty()) {
            throw new IllegalArgumentException("empty site id");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive: " + generation);
        }
        String head = namespace(namespace) + "_s_";
        String tail = "_" + generation;
        int room = maxLength - head.length() - tail.length();
        if (room < 1) {
            throw new IllegalArgumentException("no room for a site name within " + maxLength + " characters");
        }
        String base = fold(siteId);
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        return head + base + tail;
    }

    /**
     * Whether {@code name} is one this install may create or drop: {@code <ns>_app} or {@code <ns>_s_…}, only
     * {@code [a-z0-9_]}, and within the engine limit. Checked before any DDL.
     */
    public static boolean isOwnSchema(String namespace, String name, int maxLength) {
        if (name == null || name.length() > maxLength) {
            return false;
        }
        String head = namespace(namespace) + "_";
        if (!name.startsWith(head)) {
            return false;
        }
        String rest = name.substring(head.length());
        return rest.equals("app") || SITE_SCHEMA_TAIL.matcher(rest).matches();
    }

    /** Whether {@code name} is a site schema of this install (not the app schema). */
    public static boolean isSiteSchema(String namespace, String name, int maxLength) {
        return isOwnSchema(namespace, name, maxLength) && !name.equals(appSchema(namespace));
    }

    /** Throws unless {@link #isOwnSchema}: the guard in front of every CREATE, DROP and binding. */
    public static String requireOwnSchema(String namespace, String name, int maxLength) {
        if (!isOwnSchema(namespace, name, maxLength)) {
            throw new IllegalStateException("refusing a schema name outside this install's namespace: " + name);
        }
        return name;
    }

    /**
     * A plugin's object prefix: the id lower-cased, everything outside {@code [a-z0-9]} mapped to {@code _}, then
     * {@code __}; capped at {@value #PREFIX_MAX} characters by cutting and appending a hash of the full id.
     */
    public static String pluginPrefix(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isEmpty()) {
            throw new IllegalArgumentException("empty plugin id");
        }
        String folded = fold(pluginId);
        String prefix = folded + "__";
        if (prefix.length() <= PREFIX_MAX) {
            return prefix;
        }
        String hash = sha256Hex(pluginId).substring(0, 6);
        return folded.substring(0, PREFIX_MAX - 2 - 1 - hash.length()) + "_" + hash + "__";
    }

    /**
     * Whether two prefixes cannot both be recorded: equal, or one the start of the other, so an object name could
     * belong to either. Also true when either starts with the reserved {@code rw_}.
     */
    public static boolean prefixesClash(String a, String b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    /** Whether a plugin prefix is refused outright because it starts with the reserved {@code rw_}. */
    public static boolean isReservedPrefix(String prefix) {
        return prefix.startsWith(RESERVED_PREFIX);
    }

    /** Lower-cased, with every character outside {@code [a-z0-9]} mapped to {@code _}. */
    static String fold(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '_');
        }
        return out.toString();
    }

    /** The SHA-256 of a string's UTF-8 bytes, in lower-case hex. */
    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /** The SHA-256 of some bytes, in lower-case hex. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
        }
    }
}
