package mx.quest.runway.plugin;

import java.util.Objects;

/**
 * A plugin version — plain {@code MAJOR.MINOR.PATCH}, compared left-to-right as integers with missing trailing
 * segments padded with {@code 0} (so {@code 2.1} equals {@code 2.1.0}). Versions are kept deliberately plain so
 * the compare stays trivial: a <b>non-numeric or pre-release segment fails at parse</b> — e.g.
 * {@code 2.1-RC1}, {@code 2.1.x}, {@code v2} are loud descriptor errors, never a silently-lexical comparison.
 */
public record PluginVersion(int major, int minor, int patch) implements Comparable<PluginVersion> {

    /**
     * Parses a plain {@code MAJOR[.MINOR[.PATCH]]} version.
     *
     * @throws IllegalArgumentException if the text is blank, has more than three segments, or any segment is
     *                                  not a run of ASCII digits (a pre-release/qualifier segment is rejected)
     */
    public static PluginVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("version is blank");
        }
        String[] segments = raw.trim().split("\\.", -1); // -1: keep trailing empties so "2.." is rejected
        if (segments.length > 3) {
            throw new IllegalArgumentException("version '" + raw + "' has more than three segments");
        }
        int[] parts = new int[3]; // missing trailing segments stay 0
        for (int i = 0; i < segments.length; i++) {
            parts[i] = segment(segments[i], raw);
        }
        return new PluginVersion(parts[0], parts[1], parts[2]);
    }

    private static int segment(String segment, String raw) {
        if (segment.isEmpty() || !segment.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException(
                    "version '" + raw + "' has a non-numeric segment '" + segment + "' (use plain MAJOR.MINOR.PATCH)");
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("version '" + raw + "' segment '" + segment + "' is out of range", e);
        }
    }

    @Override
    public int compareTo(PluginVersion other) {
        Objects.requireNonNull(other, "other");
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
