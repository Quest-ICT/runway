package mx.quest.database.mariadb;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseProvider;
import org.mariadb.jdbc.MariaDbDataSource;

/**
 * MariaDB through MariaDB Connector/J. Builds an unpooled {@link MariaDbDataSource} whose URL is
 * {@code jdbc:mariadb://host:port/database} plus properties this class pins; the account goes in through the setters,
 * never the URL. The database plugin pools it and runs every statement.
 *
 * <p>Pinned so that no setting and no server can reach outside the connection:
 * {@code allowLocalInfile=false} (the server cannot ask for a client file), {@code allowMultiQueries=false},
 * {@code permitRedirect=false} (the server cannot send the client to another host),
 * {@code allowPublicKeyRetrieval=false}. The connection time zone is UTC, matching the session time zone the
 * database plugin pins; {@code preserveInstants} stays off, so a {@code LocalDateTime} is read and written as it is
 * (instants as UTC wall-clock values), whatever the JVM's zone. TLS: {@code DISABLE} {@code sslMode=disable}; {@code REQUIRE} {@code sslMode=trust}
 * (encrypted, the server not checked); {@code VERIFY_FULL} {@code sslMode=verify-full} against the configured CA file
 * ({@code serverSslCert}) or the JVM's trust store.
 */
public final class MariaDbProvider implements DatabaseProvider {

    static final int DEFAULT_PORT = 3306;

    @Override
    public String id() {
        return "mariadb";
    }

    @Override
    public String driverVersion() {
        String version = MariaDbDataSource.class.getPackage().getImplementationVersion();
        return "MariaDB Connector/J " + (version == null ? "" : version).strip();
    }

    @Override
    public DataSource dataSource(ConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        try {
            MariaDbDataSource ds = new MariaDbDataSource(url(settings));
            ds.setUser(settings.username());
            ds.setPassword(settings.password());
            return ds;
        } catch (SQLException e) {
            throw new IllegalArgumentException("the MariaDB connection settings are not usable", e);
        }
    }

    /** The URL: the checked host, port and database, then only the properties this class pins. */
    static String url(ConnectionSettings settings) {
        String host = settings.host().indexOf(':') >= 0 ? "[" + settings.host() + "]" : settings.host();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("allowLocalInfile", "false");
        p.put("allowMultiQueries", "false");
        p.put("permitRedirect", "false");
        p.put("allowPublicKeyRetrieval", "false");
        p.put("connectionTimeZone", "UTC");
        // No preserveInstants: with it Connector/J also turns every DATETIME/TIMESTAMP it reads as a LocalDateTime into
        // the JVM's zone, and the database plugin reads and writes instants as UTC LocalDateTime values (the session
        // is UTC). Without it a LocalDateTime goes both ways as written, whatever the JVM's zone.
        p.put("tcpKeepAlive", "true");
        p.put("connectTimeout", Long.toString(Math.max(1, settings.connectTimeout().toMillis())));
        p.put("socketTimeout", Long.toString(Math.max(1, settings.socketTimeout().toMillis())));
        switch (settings.tls()) {
            case DISABLE -> p.put("sslMode", "disable");
            case REQUIRE -> p.put("sslMode", "trust");
            case VERIFY_FULL -> {
                p.put("sslMode", "verify-full");
                if (settings.caCertificate() != null) {
                    p.put("serverSslCert", settings.caCertificate().toAbsolutePath().toString());
                }
            }
            default -> throw new IllegalArgumentException("unknown TLS policy " + settings.tls());
        }
        // Connector/J takes the URL literally (it decodes nothing), so values go in as they are: an encoded CA path
        // would name a file that does not exist. The characters its parser splits on are refused instead.
        StringBuilder url = new StringBuilder("jdbc:mariadb://").append(host).append(':')
                .append(settings.portOr(DEFAULT_PORT)).append('/').append(literal(settings.database()));
        char sep = '?';
        for (Map.Entry<String, String> e : p.entrySet()) {
            url.append(sep).append(e.getKey()).append('=').append(literal(e.getValue()));
            sep = '&';
        }
        return url.toString();
    }

    private static String literal(String value) {
        if (value.indexOf('&') >= 0 || value.indexOf('?') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("a connection value cannot contain '&', '?' or '=': " + value);
        }
        return value;
    }
}
