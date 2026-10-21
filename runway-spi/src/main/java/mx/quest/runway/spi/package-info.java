/**
 * Runway's extension API — the small set of capability-typed Service Provider Interfaces
 * (SPIs) the platform is built around.
 *
 * <p>Every "kind of X" in the domain — and the platform's own built-in features — is an
 * implementation of an SPI in this module, discovered and wired identically whether it ships
 * in the core or in a plugin (the no-two-class rule). The host is <b>capability-driven</b>: an
 * implementation declares what it can do as data, and host surfaces branch on a declared
 * capability, never on a concrete type.
 *
 * <p>This module depends on <b>nothing</b> in the host: only these interfaces and their
 * immutable model records (in {@link mx.quest.runway.spi.model}). It is RELEASED API — see
 * {@link mx.quest.runway.spi.RunwaySpi} for the compatibility contract.
 *
 * <p>The extension axes defined here ("Group A" — the JDK-only Java SPIs):
 * {@link mx.quest.runway.spi.HeadContributor}, {@link mx.quest.runway.spi.ContentWidget},
 * {@link mx.quest.runway.spi.PageQualityCheck}, {@link mx.quest.runway.spi.EndpointContributor},
 * and {@link mx.quest.runway.spi.BackupParticipant}. Each is a thin interface carrying a stable
 * {@code key()}, one lifecycle method over an immutable host-built view, and a default
 * {@code capabilities()} declaration. Host-coupled axes (servlet filters, Spring security
 * chains) and the declarative descriptor axes (settings, permissions, menu, dashboard cards)
 * live in the host module, not here.
 */
package mx.quest.runway.spi;
