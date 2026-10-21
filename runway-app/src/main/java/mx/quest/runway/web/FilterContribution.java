package mx.quest.runway.web;

import jakarta.servlet.Filter;
import mx.quest.runway.spi.FilterBand;

/**
 * A plugin's servlet-filter contribution. It is <b>host-side</b> (it references {@code jakarta.servlet.Filter},
 * so it is not part of the dependency-free released SPI) but a plugin compiles against it as a peer. Crucially,
 * a plugin declares only a {@link FilterBand} and a small {@link #offset()} — <b>never a raw order</b> — and the
 * kernel maps that through {@link PluginFilterPlacement#resolve} (clamped to the band's window). That is the
 * structural guarantee that a plugin filter can never preempt the core protection floor or run before security.
 *
 * <p>The kernel collects every {@code FilterContribution} bean and registers each as an ordered servlet filter
 * (see {@code PluginFilterRegistrar}); a plugin therefore never sets a {@code FilterRegistrationBean} order
 * itself.
 */
public interface FilterContribution {

    /** The filter to install (stateless and thread-safe — one instance serves many requests). */
    Filter filter();

    /** The band this filter belongs to (its order is computed and clamped within the band by the kernel). */
    FilterBand band();

    /** A small relative offset within the band to order this plugin's own filters ({@code 0} = the band's edge). */
    default int offset() {
        return 0;
    }

    /** A stable name for diagnostics and the servlet registration. */
    default String name() {
        return "plugin-" + getClass().getSimpleName();
    }
}
