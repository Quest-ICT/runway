package mx.quest.runway.spi.service;

import java.util.Optional;
import mx.quest.runway.spi.model.SiteView;

/**
 * Looks a site up by id, off-request as well as on one: the way a background task, a listener or a console screen
 * gets the {@link SiteView} of a site it only knows by id. A kernel singleton, injected by type.
 *
 * <p>Thread-safe; never returns {@code null}.
 *
 * @since SPI 0.5.0
 */
public interface Sites {

    /**
     * The view of site {@code siteId}. Suspended sites are included (a suspended site still exists and still owns
     * its data).
     *
     * @return the view, or empty when no site has this (well-formed) id
     * @throws IllegalArgumentException if {@code siteId} is not a safe identifier
     * @throws java.io.UncheckedIOException if the site's record cannot be read (since SPI 1.1.0; before, such a site
     *                                      read as missing): a caller must not take that for "no such site"
     */
    Optional<SiteView> find(String siteId);
}
