package mx.quest.runway.spi;

import java.util.List;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;

/**
 * An <b>advisory</b> on-page content/SEO/GEO check. The core ships structural checks (title
 * and description length, heading structure, alt text, …); SEO/GEO plugins add domain checks.
 * A check <b>reports findings, it never edits the page</b>. Findings are advisory by default;
 * only where a site opts into enforcement do they gate publishing.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #inspect(PageView)} is a <b>pure function</b> of its read-only {@link PageView};
 *       implementations are <b>stateless and thread-safe</b> and must not mutate anything.</li>
 *   <li>Return the findings (an <b>empty list</b> means "nothing to report"); <b>never
 *       {@code null}</b> — non-null is the contract. (The reference host coerces a stray
 *       {@code null} to empty defensively, but that tolerance is <em>not</em> part of the SPI
 *       contract; an alternative host may treat a {@code null} return as a fault.)</li>
 *   <li>{@link #key()} is a stable, unique identifier; a duplicate among kernel contributions fails startup; a plugin's
 *       colliding contribution is refused (the later plugin's in load order) and reported (since SPI 0.4.0).</li>
 * </ul>
 *
 * <h2>Limits &amp; failure</h2>
 * An {@code inspect} that throws a {@code RuntimeException} or {@code LinkageError} is replaced by one
 * {@code WARNING} finding with the code {@code check.failed} — never an {@code ERROR}, so a broken check can never
 * block a publish on an Enforced or Reviewed site. The failure is logged and counted. A check may run several times
 * per request (once per locale the page is offered in); keep it fast.
 *
 * <p><b>Threading.</b> The host calls this concurrently from many request threads; an implementation must be
 * stateless or internally synchronized.
 */
public interface PageQualityCheck {

    /**
     * This check's stable, unique key (for diagnostics and collision detection).
     *
     * @return a non-{@code null}, non-empty identifier, stable across releases
     */
    String key();

    /**
     * Inspects the page and reports findings, without modifying anything.
     *
     * @param page the immutable, read-only page view (never {@code null})
     * @return the findings (empty when the page is clean); never {@code null}
     */
    List<Finding> inspect(PageView page);

    /**
     * The capabilities this check declares, so the host can branch on data rather than type.
     * Defaults to {@link Capabilities#none()}.
     *
     * @return the declared capabilities (never {@code null})
     */
    default Capabilities capabilities() {
        return Capabilities.none();
    }
}
