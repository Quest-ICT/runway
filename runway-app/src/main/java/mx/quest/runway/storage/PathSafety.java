package mx.quest.runway.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Traversal-safe, tenant-scoped path and key handling — the server-side guard that a site id (or
 * any external-supplied path segment) can never escape its root or reach another tenant's data.
 *
 * <p>Enforcement lives here, never in the UI: a malformed identifier is rejected, and any derived
 * path that would resolve outside its root throws. Compound keys (for caches and the like) use a
 * printable separator, never a control character, so they stay greppable and unambiguous.
 */
public final class PathSafety {

    private PathSafety() {
    }

    /** The separator for compound keys — printable and disallowed inside any safe identifier. */
    public static final char KEY_SEPARATOR = '|';

    /** Reserved URL prefixes a public page path must never claim (kernel/operator surfaces). */
    private static final String[] RESERVED_PATH_PREFIXES = {"/actuator", "/static", "/error"};

    /**
     * A safe identifier: starts with a letter or digit, then letters, digits, {@code . _ -}. This
     * excludes path separators, {@code |}, control characters, and the {@code .}/{@code ..}
     * traversal segments.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Whether {@code id} is a safe identifier (non-null, not {@code .}/{@code ..}, matches the grammar). */
    public static boolean isSafeId(String id) {
        return id != null && !id.equals(".") && !id.equals("..") && SAFE_ID.matcher(id).matches();
    }

    /**
     * Whether {@code path} is a safe public page path — the guard for author-supplied {@code path} /
     * {@code localizedPaths} values, which become routing keys. Unlike {@link #isSafeId} this permits
     * {@code /} sub-paths and hyphens (a localized slug like {@code /acerca-de} or a multi-segment
     * {@code /productos/mi-producto}), but it must still be traversal-safe and not claim a reserved
     * surface. Requires: non-empty, starts with {@code /}, no {@code ..} or {@code //} segment, no
     * control character, not the key separator {@code |}, and not under a reserved prefix
     * ({@code /__…}, {@code /actuator}, {@code /static}, {@code /error}).
     *
     * <p>Note: this is path-<em>shape</em> safety only, and cannot see a site's locale set. A path
     * whose leading segment equals a configured non-default locale code (e.g. {@code /es/…} where the
     * site offers {@code es}) is <em>shape</em>-safe but <em>unreachable</em>: the locale-prefix parser
     * re-reads that segment as a prefix, so the page never serves at its own canonical URL. That is a
     * misconfiguration that <b>fails safe</b> — the page is silently unreachable, never served as a
     * different page — and is not rejected here. Site-scoped reachability validation (which needs the
     * site's locales) is a separate, not-yet-built concern, intended to surface as an authoring quality
     * check alongside the step-5 site-validation flow.
     */
    public static boolean isSafePagePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return false;
        }
        if (path.indexOf(KEY_SEPARATOR) >= 0 || path.contains("..") || path.contains("//")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        if (path.startsWith("/__")) {
            return false;
        }
        for (String reserved : RESERVED_PATH_PREFIXES) {
            if (path.equals(reserved) || path.startsWith(reserved + "/")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code id} if {@linkplain #isSafeId safe}, else throws.
     *
     * @throws IllegalArgumentException if the identifier is unsafe
     */
    public static String requireSafeId(String id) {
        if (!isSafeId(id)) {
            throw new IllegalArgumentException("unsafe identifier: " + id);
        }
        return id;
    }

    /**
     * Resolves {@code segments} under {@code root}, validating each segment and guaranteeing the
     * result stays within {@code root}. Defense in depth: each segment is already separator-free,
     * and the normalized result is re-checked against the root.
     *
     * @throws IllegalArgumentException if any segment is unsafe or the result escapes the root
     */
    public static Path resolveWithin(Path root, String... segments) {
        Objects.requireNonNull(root, "root");
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base;
        for (String segment : segments) {
            resolved = resolved.resolve(requireSafeId(segment));
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("path escapes its root: " + resolved);
        }
        return resolved;
    }

    /**
     * Joins {@code parts} into a compound key with {@link #KEY_SEPARATOR}. Each part must be
     * non-null and must not contain the separator (so the key parses back unambiguously).
     *
     * @throws IllegalArgumentException if a part contains the separator
     */
    public static String compoundKey(String... parts) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = Objects.requireNonNull(parts[i], "key part");
            if (part.indexOf(KEY_SEPARATOR) >= 0) {
                throw new IllegalArgumentException("key part contains the separator '" + KEY_SEPARATOR + "': " + part);
            }
            if (i > 0) {
                key.append(KEY_SEPARATOR);
            }
            key.append(part);
        }
        return key.toString();
    }

    /**
     * Resolves a relative path under {@code root}, guaranteeing the result stays within it. Unlike
     * {@link #requireSafeId}, this permits richer names — a leading underscore (master templates
     * like {@code _master}), dots, and {@code /} sub-paths — because the containment check, not a
     * per-segment grammar, is the guard. A name that escapes the root (via {@code ..}, an absolute
     * path, etc.) is rejected.
     *
     * @throws IllegalArgumentException if the relative path is blank or escapes the root
     */
    public static Path resolveConfined(Path root, String relativePath) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("empty relative path");
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("path escapes its root: " + relativePath);
        }
        return resolved;
    }

    /**
     * The real (symlink-resolved) path of the deepest ancestor of {@code path} that exists, with any not-yet-created
     * remainder re-appended. Real-pathing the deepest existing ancestor (rather than the whole path, which may not
     * exist yet on a write) is what lets a symlinked directory be detected while a brand-new leaf still resolves
     * cleanly. A confinement check runs both the target and its root through this, so a symlinked storage root
     * (a temp dir under {@code /tmp} on some systems) resolves consistently on both sides.
     *
     * @throws IOException if an existing component cannot be resolved (for example a dangling symbolic link) — a
     *                     caller enforcing confinement must then refuse
     */
    public static Path effectiveRealPath(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalized; // nothing along the path exists — purely lexical, already confined
        }
        Path remainder = existing.relativize(normalized);
        Path realExisting = existing.toRealPath();
        return remainder.toString().isEmpty() ? realExisting : realExisting.resolve(remainder);
    }
}
