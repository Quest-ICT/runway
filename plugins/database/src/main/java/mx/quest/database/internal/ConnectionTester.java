package mx.quest.database.internal;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The console's two connection tests. Each answers with a fixed category (and, for success, the server version);
 * the driver's own message goes only to the server log, never with a credential.
 *
 * <ul>
 *   <li><b>Running</b>: borrows from the live pool and asks the server what it is.</li>
 *   <li><b>New settings</b>: opens one throwaway connection with short timeouts from typed values and saves nothing.
 *       A blank password reuses the running one only when every setting the password is bound to (provider, host,
 *       port, database, username, TLS, CA certificate) matches the running value, so the test cannot send the saved
 *       password to a server of the tester's choosing.</li>
 * </ul>
 */
public final class ConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTester.class);

    static final Duration TEST_CONNECT = Duration.ofSeconds(5);
    static final Duration TEST_SOCKET = Duration.ofSeconds(15);

    /** The categories the console shows. */
    public enum Category {
        OK, INVALID_SETTINGS, NO_PROVIDER, UNREACHABLE, TLS, AUTHENTICATION, DATABASE_MISSING, UNSUPPORTED_SERVER,
        NOT_AVAILABLE, OTHER
    }

    /** A test's answer: the category and a detail safe to show (a server version or a settings problem). */
    public record Result(Category category, String detail) {
    }

    private final DatabaseRuntime runtime;
    private final Supplier<List<DatabaseProvider>> providers;
    private final Path workDir;

    public ConnectionTester(DatabaseRuntime runtime, Supplier<List<DatabaseProvider>> providers, Path workDir) {
        this.runtime = runtime;
        this.providers = providers;
        this.workDir = workDir;
    }

    /** Tests the live pool. */
    public Result running() {
        if (!runtime.available()) {
            return new Result(Category.NOT_AVAILABLE, runtime.health().detail());
        }
        try (Connection c = runtime.borrow()) {
            return new Result(Category.OK, runtime.engine().inspectServer(c));
        } catch (SQLException | RuntimeException e) {
            log.warn("database: running-connection test failed: {}", DatabaseRuntime.describe(e));
            return new Result(e instanceof SQLException sql ? category(runtime.engine().classify(sql))
                    : Category.UNREACHABLE, "");
        }
    }

    /**
     * Tests typed settings.
     *
     * @param props    the typed values
     * @param password the typed password; blank to reuse the running one where the rule allows
     */
    public Result newSettings(DatabaseProps props, String password) {
        DatabaseConfig typed;
        try {
            typed = DatabaseConfig.from(props, Optional.empty());
        } catch (IllegalArgumentException e) {
            return new Result(Category.INVALID_SETTINGS, e.getMessage());
        }
        String secret = password == null ? "" : password;
        if (secret.isEmpty()) {
            DatabaseConfig running = runtime.config();
            if (running != null && sameTarget(running, typed)) {
                secret = running.password();
            }
        }
        DatabaseConfig config;
        try {
            config = DatabaseConfig.from(props, Optional.of(secret));
        } catch (IllegalArgumentException e) {
            return new Result(Category.INVALID_SETTINGS, e.getMessage());
        }
        List<DatabaseProvider> matching = providers.get().stream().filter(p -> config.provider().equals(p.id()))
                .toList();
        if (matching.size() != 1) {
            return new Result(Category.NO_PROVIDER, "database-" + config.provider());
        }
        Engine engine = DatabaseRuntime.engineFor(config.provider());
        try {
            Path ca = DatabaseRuntime.writeCa(workDir, config.caCertificate());
            DataSource source = matching.get(0).dataSource(new ConnectionSettings(config.host(), config.port(),
                    config.database(), config.username(), config.password(), config.tls(), ca, TEST_CONNECT,
                    TEST_SOCKET));
            try (Connection c = source.getConnection()) {
                return new Result(Category.OK, engine.inspectServer(c));
            }
        } catch (SQLException e) {
            log.warn("database: new-settings test against {} failed: {}", config, DatabaseRuntime.describe(e));
            return new Result(category(engine.classify(e)), e instanceof UnsupportedServerException ? e.getMessage()
                    : "");
        } catch (Exception e) {
            log.warn("database: new-settings test against {} failed: {}", config, DatabaseRuntime.describe(e));
            return new Result(Category.OTHER, "");
        }
    }

    /** Whether two settings point the password at the same place as the same account. */
    static boolean sameTarget(DatabaseConfig a, DatabaseConfig b) {
        return a.provider().equals(b.provider()) && a.host().equals(b.host()) && a.port() == b.port()
                && a.database().equals(b.database()) && a.username().equals(b.username()) && a.tls() == b.tls()
                && Objects.equals(a.caCertificate(), b.caCertificate());
    }

    private static Category category(Engine.ConnectFailure failure) {
        return switch (failure) {
            case UNREACHABLE -> Category.UNREACHABLE;
            case TLS -> Category.TLS;
            case AUTHENTICATION -> Category.AUTHENTICATION;
            case DATABASE_MISSING -> Category.DATABASE_MISSING;
            case UNSUPPORTED_SERVER -> Category.UNSUPPORTED_SERVER;
            default -> Category.OTHER;
        };
    }
}
