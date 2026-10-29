package mx.quest.database.spi;

import mx.quest.runway.spi.model.SiteRef;

/**
 * One consumer plugin's handle on the database, from {@link Databases#of}. Everything it opens is bound to that
 * plugin: its migrations, its object prefix, its failure state.
 *
 * <h2>Choosing the site</h2>
 * <b>Never take a site id from request input</b> (a form field, a query parameter, a path segment): a site's admin
 * could then name another site. Use {@link #currentSite()} while serving a request, {@link #site(SiteRef)} with the
 * reference a Runway callback handed you (a backup, a site event), and {@link #siteInBackground(String)} only in
 * background or application-console code that got the id from Runway.
 *
 * <p>Thread-safe.
 */
public interface PluginDatabase {

    /**
     * Whether a database is configured, its provider is installed, and the server answered and passed the
     * provider's checks. It can change at any time (a restart, an outage, the server coming back).
     *
     * @return {@code true} when calls are expected to work
     */
    boolean available();

    /**
     * The running database's dialect, for the few statements that differ between products.
     *
     * @return the dialect
     * @throws DatabaseUnavailableException when {@link #available()} is false
     */
    Dialect dialect();

    /**
     * The prefix every object this plugin's scripts create must start with: the plugin id lower-cased, every other
     * character turned into {@code _}, then {@code __} (for example {@code runway_demo_database__}), shortened with
     * a hash past 24 characters. A script that creates an object without it is refused.
     *
     * @return the prefix
     */
    String prefix();

    /**
     * The scope of the site the current request is for.
     *
     * @return that site's scope
     * @throws IllegalStateException outside a request for a site (an application-console request, a background
     *                               thread)
     */
    Scope currentSite();

    /**
     * The scope of a site Runway named to you, through a {@link SiteRef} it handed you.
     *
     * @param site the site
     * @return that site's scope
     * @throws IllegalArgumentException when no such site exists
     */
    Scope site(SiteRef site);

    /**
     * The scope of a site, by id, for background and application-console code that got the id from Runway (a site
     * listing, a scheduled task). Not for ids that arrived in a request.
     *
     * @param siteId the site id
     * @return that site's scope
     * @throws IllegalArgumentException when no such site exists
     */
    Scope siteInBackground(String siteId);

    /**
     * The installation-wide scope, for data that belongs to no site. Keep a site's data in its site scope: the app
     * scope is in no site backup, and does not move when the operator changes the database.
     *
     * @return the app scope
     * @throws IllegalStateException when the consumer did not declare {@link Scope.Kind#APP}
     */
    Scope app();
}
