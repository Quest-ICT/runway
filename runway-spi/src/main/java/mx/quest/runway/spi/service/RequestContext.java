package mx.quest.runway.spi.service;

import java.util.Optional;
import mx.quest.runway.spi.model.SiteView;

/**
 * The tenancy the request on the calling thread is bound to — what a plugin's console screen asks to learn which
 * site the signed-in user is managing. A kernel singleton, injected by type.
 *
 * <p>An endpoint does not need this: its {@link mx.quest.runway.spi.model.RouteRequest} already carries the site,
 * the locale, the client IP and the origin.
 *
 * <p>Thread-safe; never returns {@code null}; never throws.
 *
 * @since SPI 0.5.0
 */
public interface RequestContext {

    /**
     * The site this request is bound to: the site whose public page or tenant console ({@code /__admin/…}) is
     * being served.
     *
     * @return the site; empty on the <b>application</b> realm ({@code /__app/…}, where the host would otherwise fall
     *         back to the catch-all default site, which is not what an application-realm screen manages) and off a
     *         request thread
     */
    Optional<SiteView> site();
}
