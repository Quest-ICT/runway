package mx.quest.runway.spi.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable set of capability flags an SPI implementation <b>declares</b> about itself,
 * so the host can be <b>capability-driven</b>: every host surface branches on a declared
 * capability (data), never on a concrete implementation type or an {@code instanceof}/
 * {@code switch} over a known catalog. The acceptance test of the model is that a brand-new
 * implementation lights up across the platform with no host edit.
 *
 * <p>Flags are free-form string identifiers, by convention lowercase dotted keys (for example
 * {@code "widget.configurable"} or {@code "head.per-locale"}). They are a <em>convention the
 * kernel owns</em> — only the host calls {@link #has(String)} to decide behaviour, so an
 * implementation built against an older SPI never needs to know a flag a newer host added,
 * and a host that doesn't recognise a flag simply doesn't branch on it. This is deliberately
 * a string vocabulary (not a closed enum) so the catalog grows additively without an SPI
 * binary change and without the "old binary lacks the new enum constant" trap.
 *
 * <p>Instances are immutable; {@link #flags()} returns an unmodifiable set.
 *
 * @param flags the declared capability identifiers (never {@code null}; defensively copied)
 */
public record Capabilities(Set<String> flags) {

    private static final Capabilities NONE = new Capabilities(Set.of());

    /**
     * Canonical constructor — null-normalizes to the empty set and stores an unmodifiable
     * copy. Rejects a {@code null} flag element.
     */
    public Capabilities {
        flags = (flags == null) ? Set.of() : Set.copyOf(flags);
    }

    /** The empty capability set — the default an implementation advertises when it declares nothing. */
    public static Capabilities none() {
        return NONE;
    }

    /**
     * A capability set from the given flags. Duplicates collapse; a {@code null} flag is
     * rejected. No arguments (or {@code null}) yields {@link #none()}.
     */
    public static Capabilities of(String... flags) {
        if (flags == null || flags.length == 0) {
            return NONE;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(Arrays.asList(flags));
        return new Capabilities(set);
    }

    /** Whether this set declares the given capability flag. A {@code null} flag is never present. */
    public boolean has(String flag) {
        return flag != null && flags.contains(flag);
    }

    /** Whether no capability is declared. */
    public boolean isEmpty() {
        return flags.isEmpty();
    }

    /**
     * This set plus the given flags (a new instance; this one is unchanged). Useful for an
     * implementation composing its capabilities.
     */
    public Capabilities with(String... more) {
        if (more == null || more.length == 0) {
            return this;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(flags);
        set.addAll(Arrays.asList(more));
        for (String f : set) {
            Objects.requireNonNull(f, "capability flag");
        }
        return new Capabilities(set);
    }
}
