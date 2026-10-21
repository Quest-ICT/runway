package mx.quest.runway.spi;

/**
 * The five conceptual request-pipeline bands. A plugin filter contribution declares its
 * <b>band</b> — never a raw order — and the host maps that to a concrete order clamped to the band's window, so
 * a plugin can never escape its band to preempt the protection floor or run before the security chain. This is
 * the released, dependency-free identity plugins compile against; the concrete integer windows and clamp live
 * host-side.
 *
 * <ul>
 *   <li>{@link #EDGE_INGRESS} — raw request, pre-security (reject/normalize); never before the kernel floor.</li>
 *   <li>{@link #EDGE_EGRESS} — response rewriting (post-phase).</li>
 *   <li>{@link #TENANT} — after the site is resolved, before security.</li>
 *   <li>{@link #SECURITY} — <b>not contributable</b>: the kernel's realm security chains own it. A plugin cannot
 *       add a security chain instead either &mdash; since SPI 1.0.0 a non-essential plugin that declares a
 *       {@code SecurityFilterChain} bean is blocked (the Spring-context rules of the plugin contract).</li>
 *   <li>{@link #APPLICATION} — feature filters; the default, inner of security.</li>
 * </ul>
 */
public enum FilterBand {

    EDGE_INGRESS(true),
    EDGE_EGRESS(true),
    TENANT(true),
    SECURITY(false),
    APPLICATION(true);

    private final boolean contributable;

    FilterBand(boolean contributable) {
        this.contributable = contributable;
    }

    /** Whether a plugin filter may target this band (false for {@link #SECURITY}). */
    public boolean contributable() {
        return contributable;
    }
}
