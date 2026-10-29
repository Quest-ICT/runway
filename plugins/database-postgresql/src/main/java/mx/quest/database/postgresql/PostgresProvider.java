package mx.quest.database.postgresql;

import java.util.Objects;
import javax.sql.DataSource;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseProvider;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.DriverInfo;

/**
 * PostgreSQL through pgjdbc. Builds an unpooled {@link PGSimpleDataSource} from the database plugin's checked
 * settings: one host, the account as properties, TLS exactly as configured, and nothing taken from free text. The
 * database plugin pools it and runs every statement.
 *
 * <p>TLS:
 * <ul>
 *   <li>{@code DISABLE}: {@code sslmode=disable}.</li>
 *   <li>{@code REQUIRE}: {@code sslmode=require} with the non-validating factory: encrypted, the server not
 *       checked, no client certificate (the factory initialises its TLS context with no key manager).</li>
 *   <li>{@code VERIFY_FULL}: {@code sslmode=verify-full}, checking the chain and the host name, against the
 *       configured CA file when there is one ({@code sslrootcert}) and the JVM's trust store otherwise.</li>
 * </ul>
 */
public final class PostgresProvider implements DatabaseProvider {

    static final int DEFAULT_PORT = 5432;
    static final String NON_VALIDATING = "org.postgresql.ssl.NonValidatingFactory";
    static final String JVM_TRUST = "org.postgresql.ssl.DefaultJavaSSLFactory";
    static final String CA_FILE = "org.postgresql.ssl.LibPQFactory";

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public String driverVersion() {
        return "pgjdbc " + DriverInfo.DRIVER_VERSION;
    }

    @Override
    public DataSource dataSource(ConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        PGSimpleDataSource ds = new PGSimpleDataSource();
        String host = settings.host().indexOf(':') >= 0 ? "[" + settings.host() + "]" : settings.host();
        ds.setServerNames(new String[] {host});
        ds.setPortNumbers(new int[] {settings.portOr(DEFAULT_PORT)});
        ds.setDatabaseName(settings.database());
        ds.setUser(settings.username());
        ds.setPassword(settings.password());
        ds.setApplicationName("runway");
        ds.setConnectTimeout(seconds(settings.connectTimeout().toSeconds()));
        ds.setLoginTimeout(seconds(settings.connectTimeout().toSeconds()));
        ds.setSocketTimeout(seconds(settings.socketTimeout().toSeconds()));
        ds.setTcpKeepAlive(true);
        switch (settings.tls()) {
            case DISABLE -> ds.setSslMode("disable");
            case REQUIRE -> {
                ds.setSslMode("require");
                ds.setSslfactory(NON_VALIDATING);
            }
            case VERIFY_FULL -> {
                ds.setSslMode("verify-full");
                if (settings.caCertificate() != null) {
                    ds.setSslfactory(CA_FILE);
                    ds.setSslRootCert(settings.caCertificate().toAbsolutePath().toString());
                } else {
                    ds.setSslfactory(JVM_TRUST);
                }
            }
            default -> throw new IllegalArgumentException("unknown TLS policy " + settings.tls());
        }
        return ds;
    }

    private static int seconds(long value) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, value));
    }
}
