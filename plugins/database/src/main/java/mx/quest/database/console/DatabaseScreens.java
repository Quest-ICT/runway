package mx.quest.database.console;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import mx.quest.database.internal.DatabaseBackup;
import mx.quest.database.internal.DatabaseConfig;
import mx.quest.database.internal.DatabaseCore;
import mx.quest.database.internal.DatabaseRuntime;
import mx.quest.database.internal.DatabasesImpl;
import mx.quest.database.internal.Registry;
import mx.quest.database.internal.ScreenQueries;

/**
 * What the database screens show, built per request: the application console's health screen and the one line on
 * a site's dashboard. Every database read here is bounded and failure-tolerant: a screen about an unreachable
 * database must still render.
 */
public final class DatabaseScreens {

    /** The health screen. Never holds a credential. */
    public record Health(DatabaseRuntime.Health runtime, String provider, String namespace, String target,
                         String tls, int liveSchemas, List<Registry.Node> nodes, List<Registry.Issue> issues,
                         List<String> consumers, List<String> appSchemaPlugins, String largestBackup,
                         boolean backupBudgetWarning, String lastRestoreFailure, String problem) {

        /** Whether the database is in use now. */
        public boolean available() {
            return runtime.status() == DatabaseRuntime.Status.AVAILABLE && !runtime.outage();
        }
    }

    /** A site's dashboard line. */
    public record SiteLine(boolean hasData, String size, String lastRestore, boolean lastRestoreOk) {
    }

    static final Duration NODE_WINDOW = Duration.ofMinutes(5);

    private final DatabaseCore core;
    private final DatabasesImpl databases;
    private final DatabaseBackup backup;

    public DatabaseScreens(DatabaseCore core, DatabasesImpl databases, DatabaseBackup backup) {
        this.core = core;
        this.databases = databases;
        this.backup = backup;
    }

    /** The application console's health screen. */
    public Health health() {
        DatabaseRuntime runtime = core.runtime();
        DatabaseConfig c = runtime.config();
        int live = 0;
        List<Registry.Node> nodes = List.of();
        List<Registry.Issue> issues = List.of();
        List<String> appPlugins = List.of();
        String problem = null;
        if (runtime.available()) {
            try {
                Registry registry = runtime.registry();
                live = registry.liveSchemaCount();
                nodes = registry.nodes(NODE_WINDOW);
                issues = registry.issues();
                appPlugins = ScreenQueries.appSchemaPlugins(runtime);
            } catch (SQLException | RuntimeException e) {
                problem = DatabaseRuntime.describe(e);
            }
        }
        List<String> consumers = new ArrayList<>(new TreeSet<>(databases.consumers().stream()
                .map(x -> x.pluginId()).toList()));
        String largest = null;
        boolean warn = false;
        Optional<Map.Entry<String, Long>> big = backup.largestBackup();
        if (big.isPresent() && c != null) {
            largest = big.get().getKey() + ": " + mib(big.get().getValue()) + " / " + mib(c.backupBudgetBytes());
            warn = big.get().getValue() * 4 >= c.backupBudgetBytes() * 3;
        }
        String restoreFailure = backup.lastFailure().map(e -> e.getKey() + " — " + e.getValue().at() + ": "
                + e.getValue().message()).orElse(null);
        return new Health(runtime.health(), c == null ? "" : c.provider(), c == null ? "" : c.namespace(),
                c == null ? "" : c.host() + (c.port() == 0 ? "" : ":" + c.port()) + "/" + c.database(),
                c == null ? "" : c.tls().name().toLowerCase().replace('_', '-'), live, nodes, issues, consumers,
                appPlugins, largest, warn, restoreFailure, problem);
    }

    /** A site's dashboard line. */
    public SiteLine site(String siteId) {
        if (siteId == null) {
            return new SiteLine(false, "", "", true);
        }
        boolean has;
        try {
            has = core.schemas().hasToken(siteId);
        } catch (java.io.IOException unreadable) {
            has = true; // the folder cannot be read: show the line, never "no data"
        }
        String size = "";
        DatabaseRuntime runtime = core.runtime();
        if (has && runtime.available()) {
            try {
                java.util.OptionalLong bytes = ScreenQueries.siteSize(runtime, siteId);
                size = bytes.isPresent() ? mib(bytes.getAsLong()) : "";
            } catch (SQLException | RuntimeException e) {
                size = "";
            }
        }
        Optional<DatabaseBackup.Outcome> outcome = backup.outcome(siteId);
        return new SiteLine(has, size, outcome.map(o -> o.at().toString() + " — " + o.message()).orElse(""),
                outcome.map(DatabaseBackup.Outcome::ok).orElse(true));
    }

    static String mib(long bytes) {
        return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    static String at(Instant at) {
        return at == null ? "" : at.toString();
    }
}
