package mx.quest.database.internal;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection tests on the database plugin's settings page. Each answers with a fixed category (and, for success,
 * the server version); the driver's own message goes only to the server log, never with a credential.
 *
 * <ul>
 *   <li><b>Running</b>: borrows from the live pool and asks the server what it is.</li>
 *   <li><b>Saved</b>: opens one throwaway connection with short timeouts from the settings saved on one provider
 *       plugin's page, which the next restart would apply, and changes nothing. The test takes no typed values: a
 *       form with a username and a password box is one a browser fills in with the operator's own saved login,
 *       which a test would then send to whatever server the form named (the 1.x test did exactly that).</li>
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
    private final DatabaseProps props;
    private final Supplier<List<DatabaseProvider>> providers;
    private final Path workDir;

    public ConnectionTester(DatabaseRuntime runtime, DatabaseProps props, Supplier<List<DatabaseProvider>> providers,
                            Path workDir) {
        this.runtime = runtime;
        this.props = props;
        this.providers = providers;
        this.workDir = workDir;
    }

    /** The products whose provider plugin is installed, each once, in the order found: what "Saved" can test. */
    public List<String> testable() {
        return ProviderChoice.supported(providers.get()).stream().map(DatabaseProvider::id).distinct().toList();
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
     * Tests the settings saved on one provider plugin's page.
     *
     * @param product {@code postgresql} or {@code mariadb}
     */
    public Result saved(String product) {
        List<DatabaseProvider> matching = ProviderChoice.supported(providers.get()).stream()
                .filter(p -> p.id().equals(product)).toList();
        if (matching.size() != 1) {
            return new Result(Category.NO_PROVIDER, ProviderChoice.plugin(String.valueOf(product)));
        }
        DatabaseConfig config;
        try {
            config = ProviderChoice.saved(props, matching.get(0)).config();
        } catch (IllegalArgumentException e) {
            return new Result(Category.INVALID_SETTINGS, e.getMessage());
        } catch (RuntimeException e) {
            // A provider whose saved settings cannot be read; its message is not trusted to be free of a secret.
            log.warn("database: the saved settings of {} could not be read ({})", ProviderChoice.plugin(product),
                    e.getClass().getSimpleName());
            return new Result(Category.OTHER, "");
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
            log.warn("database: saved-settings test against {} failed: {}", config, DatabaseRuntime.describe(e));
            return new Result(category(engine.classify(e)), e instanceof UnsupportedServerException ? e.getMessage()
                    : "");
        } catch (Exception e) {
            log.warn("database: saved-settings test against {} failed: {}", config, DatabaseRuntime.describe(e));
            return new Result(Category.OTHER, "");
        }
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
