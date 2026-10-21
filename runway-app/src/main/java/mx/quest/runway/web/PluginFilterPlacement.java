package mx.quest.runway.web;

import mx.quest.runway.spi.FilterBand;

/**
 * Maps a plugin filter's declared ({@link FilterBand}, offset) onto a concrete {@link FilterOrder} integer,
 * <b>clamped to the band's window</b>. The {@link FilterBand} identity is released SPI (dependency-free); the
 * concrete integer windows and the clamp authority stay here, host-side. This is the one function that enforces
 * the band guarantee: whatever offset a plugin supplies, the result can never escape the band — so a plugin can
 * never preempt the kernel protection floor (EDGE_INGRESS clamps to {@code CORE_PROTECTION + 1} at the outer
 * edge) nor run before security (APPLICATION clamps inside its window, inner of {@link FilterOrder#CORE_SECURITY}).
 *
 * <p>The disjoint, correctly-nested windows (outermost → innermost): EDGE_INGRESS {@code [CORE_PROTECTION+1,
 * -1000]}, EDGE_EGRESS {@code [-149, -1]}, TENANT {@code [71, 99]}, APPLICATION {@code [1000, 1_000_000]}.
 * {@link FilterBand#SECURITY} is not reachable: it belongs to the kernel's realm security chains, and a non-essential
 * plugin may not declare a {@code SecurityFilterChain} bean either ({@code PluginSurfaceCheck} blocks it). Requesting
 * it is a programming error.
 */
public final class PluginFilterPlacement {

    /** A band's inclusive {@code [outer, inner]} order window on the {@link FilterOrder} scale (lower = outer). */
    public record Window(int outer, int inner) {
    }

    private PluginFilterPlacement() {
    }

    /** The order window for a contributable band. */
    public static Window window(FilterBand band) {
        return switch (band) {
            case EDGE_INGRESS -> new Window(FilterOrder.CORE_PROTECTION + 1, -1000);
            case EDGE_EGRESS -> new Window(-149, -1);
            case TENANT -> new Window(71, 99);
            case APPLICATION -> new Window(1000, 1_000_000);
            case SECURITY -> throw new IllegalArgumentException(
                    "SECURITY band is not reachable by a plugin filter; it belongs to the kernel's realm security chains");
        };
    }

    /**
     * Resolves the concrete filter order for a plugin contribution: {@code offset 0} is the band's outermost
     * slot, a larger offset moves inner, and the result is clamped to the band's window (so it can never escape).
     *
     * @throws IllegalArgumentException if the band is not contributable ({@link FilterBand#SECURITY})
     */
    public static int resolve(FilterBand band, int offset) {
        if (!band.contributable()) {
            throw new IllegalArgumentException("band " + band + " is not reachable by a plugin FilterContribution");
        }
        Window window = window(band);
        long raw = (long) window.outer() + offset; // long: outer can be Integer.MIN_VALUE + 2
        return (int) Math.max(window.outer(), Math.min(window.inner(), raw));
    }
}
