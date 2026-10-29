package mx.quest.database.spi;

import javax.sql.DataSource;

/**
 * A database product, contributed by a provider plugin that ships the product's JDBC driver in its {@code lib/}.
 * The operator picks one by its {@link #id()} in the database plugin's {@code provider} setting.
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
}
