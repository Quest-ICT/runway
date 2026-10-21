/**
 * Immutable boundary model for the Runway SPI — the host-materialized <em>views</em> that
 * {@linkplain mx.quest.runway.spi SPI implementations} receive as inputs and the plain value
 * types they return.
 *
 * <p>Every type here is immutable and built by the host. An SPI implementation is a
 * <b>pure function of its inputs</b>: it never reads persistence, never sees drafts, other
 * versions, other tenants, or other requests, and returns plain values. This keeps
 * implementations trivially unit-testable (no Spring, no I/O), preserves tenant isolation,
 * and lets the host evolve storage without touching a single implementation.
 *
 * <p>Records here defensively copy and null-normalize their collection/array components in
 * their compact constructors, so a view handed to an implementation cannot be mutated by it
 * or by whoever built it. These types are <b>released API</b>: evolve them only by
 * <i>adding</i> components while keeping an older-arity constructor/factory, never by
 * reordering, retyping, or removing existing components (see
 * {@link mx.quest.runway.spi.RunwaySpi}).
 *
 * <p><b>Record patterns are not source-stable across MINOR versions.</b> Adding a component keeps every existing
 * constructor and accessor working (binary compatibility), but a deconstruction pattern names <em>all</em>
 * components ({@code if (view instanceof WidgetView(var site, var page, ...))}), so it stops compiling when one is
 * added. Read components through their accessors; use record patterns only in code you rebuild with each SPI.
 *
 * <p>Field shapes are deliberately minimal for now and grow (add-only) as each extension
 * axis is built out.
 */
package mx.quest.runway.spi.model;
