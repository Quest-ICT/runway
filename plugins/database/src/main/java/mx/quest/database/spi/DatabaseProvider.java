package mx.quest.database.spi;

import java.util.Optional;
import javax.sql.DataSource;

/**
 * A database product, contributed by a provider plugin that ships the product's JDBC driver in its {@code lib/}.
 * When both providers are installed, the operator picks one by its {@link #id()} in the database plugin's
 * {@code provider} setting; with one installed, that one is used.
 *
 * <p><b>The provider holds its server's settings</b> (since database 2.0.0): host, port, database, account,
 * password and TLS are set on the provider plugin's own settings page, and handed over through
 * {@link #runningSettings()} and {@link #savedSettings()}. The database plugin keeps only what applies to every
 * product: the provider choice, the namespace and the limits. Those two methods hand over the password in clear and
 * are for the database plugin alone; a consumer plugin never calls them. They are not a security boundary: plugins
 * are trusted code in the same JVM, as the kernel's plugin guide says, and could read the settings file anyway.
 *
 * <p><b>A provider brings the driver, not the SQL.</b> Every statement Runway runs on the provider's connections —
 * schema creation and removal, locks, the ownership checks, migrations, backup and restore — is written in the
 * database plugin for each product it supports. So the checks that keep plugins and sites apart are reviewed in one
 * place and a provider cannot weaken them. A provider's {@code id()} must therefore name a product the installed
 * database plugin supports; an unknown id is refused with a reason on the health screen.
 *
 * <p>Register the provider as one bean in the provider plugin's {@code @Configuration}; the database plugin collects
 * providers by type. Two providers with the same id are both refused.
 */
public interface DatabaseProvider {

    /** The product id the {@code provider} setting names: {@code postgresql} or {@code mariadb}. */
    String id();

    /** The bundled driver's name and version, for the health screen, e.g. {@code "pgjdbc 42.7.10"}. */
    String driverVersion();

    /**
     * An unpooled {@code DataSource} built from the provider's own driver classes, never through
     * {@code DriverManager}. The database plugin pools it.
     *
     * <ul>
     *   <li>The URL is {@code scheme://host:port/database} and nothing more; the account goes in as properties.</li>
     *   <li>The provider pins every driver property that could reach outside the connection: no local file
     *       loading, no multi-statement queries, no redirects, no logger or socket factory taken from settings.</li>
     *   <li>TLS follows {@link ConnectionSettings#tls()} exactly; {@code VERIFY_FULL} checks the host name.</li>
     *   <li>Opening a connection honours {@link ConnectionSettings#connectTimeout()}, and reads honour
     *       {@link ConnectionSettings#socketTimeout()}, with TCP keep-alive on.</li>
     * </ul>
     *
     * Building the {@code DataSource} must not connect.
     *
     * @param settings the checked settings
     * @return a new, unpooled data source
     */
    DataSource dataSource(ConnectionSettings settings);

    /**
     * The server settings the running application started with: what the database plugin connects with. Read from
     * the provider plugin's own app-level settings, the password in clear
     * ({@code PluginSettings.appSecret("password")}).
     *
     * @return the settings; empty only from a provider built before database 2.0.0, which the database plugin
     *         reports as too old
     * @since database 2.0.0
     */
    default Optional<ProviderSettings> runningSettings() {
        return Optional.empty();
    }

    /**
     * The server settings as saved now, which the next restart applies ({@code PluginSettings.savedApp}): what the
     * connection test on the database plugin's settings page tries, so an operator can check a change before
     * restarting.
     *
     * @return the settings; empty only from a provider built before database 2.0.0
     * @since database 2.0.0
     */
    default Optional<ProviderSettings> savedSettings() {
        return Optional.empty();
    }
}
