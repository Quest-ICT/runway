package mx.quest.runway.spi.service;

import java.util.Optional;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.StarterPage;

/**
 * Installs a plugin's <b>starter page</b> on a site (the contact form's page, the search results page) and reports
 * where an existing one lives. A kernel singleton, injected by type.
 *
 * <p>Installing goes through the site's editorial rules, so a plugin can never publish around them:
 * <ol>
 *   <li>An existing page with the slug (in any state) is never touched: {@link InstallResult.Outcome#ALREADY_EXISTS}.</li>
 *   <li>Otherwise the page is created as a <b>draft</b>.</li>
 *   <li>Then, by the site's editorial level:
 *     <ul>
 *       <li><b>Advisory</b>: published at once, {@link InstallResult.Outcome#PUBLISHED}.</li>
 *       <li><b>Enforced</b>: published if the quality checks pass, else it stays a draft:
 *           {@link InstallResult.Outcome#DRAFT_BLOCKED} with the blocking findings.</li>
 *       <li><b>Reviewed</b>: if the current principal holds {@code APPROVE_PAGES}, as Enforced; otherwise it is
 *           submitted to the review queue, {@link InstallResult.Outcome#PENDING_REVIEW}.</li>
 *     </ul>
 *   </li>
 * </ol>
 * The whole sequence holds the page's write lock, and each step is written to the site's audit log under the
 * current principal's name. The calling plugin is responsible for checking that the current principal may install
 * pages at all (with {@link AccessCheck}) before calling.
 *
 * <p>Thread-safe; never returns {@code null}.
 *
 * @since SPI 0.5.0
 */
public interface SitePages {

    /**
     * The default path of the page with this slug, whatever its state (draft, published, in review) — so a plugin
     * can link to "its" page even after an operator moved it.
     *
     * @return the page's path; empty when no page has the slug or the site does not exist
     * @throws IllegalArgumentException if {@code siteId} or {@code slug} is not a safe identifier
     */
    Optional<String> pathOf(String siteId, String slug);

    /**
     * Installs {@code page} on site {@code siteId} once, following the editorial rules above. Idempotent: it never
     * overwrites.
     *
     * @return what happened, the page's path and, for {@link InstallResult.Outcome#DRAFT_BLOCKED}, the blocking
     *         findings
     * @throws IllegalArgumentException if the site id or slug is unsafe, or the path is unsafe or reserved (a
     *                                  kernel surface such as {@code /__admin} or {@code /login})
     * @throws IllegalStateException    if the site does not exist, or the page could not be written (another page
     *                                  already claims the path, the master template is missing, an I/O failure)
     */
    InstallResult install(String siteId, StarterPage page);
}
