package mx.quest.database.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The few reads the console screens need from inside this package. */
public final class ScreenQueries {

    private ScreenQueries() {
    }

    /** The plugins that keep tables in the app schema (they do not move with a change of database). */
    public static List<String> appSchemaPlugins(DatabaseRuntime runtime) throws SQLException {
        try (Connection c = runtime.borrow()) {
            Map<String, String> prefixes = runtime.registry().prefixes(c);
            TreeSet<String> plugins = new TreeSet<>();
            for (String table : runtime.engine().tables(c, runtime.registry().appSchema()).keySet()) {
                String owner = DatabaseBackup.owner(table, prefixes);
                if (owner != null) {
                    plugins.add(owner);
                }
            }
            return List.copyOf(plugins);
        }
    }

    /** The size of a site's live schema in bytes, read on the main pool (it fails fast); empty when it has none. */
    public static java.util.OptionalLong siteSize(DatabaseRuntime runtime, String siteId) throws SQLException {
        try (Connection c = runtime.borrow()) {
            java.util.Optional<Registry.SiteRow> row = runtime.registry().site(c, siteId);
            if (row.isEmpty() || !Names.isSiteSchema(runtime.config().namespace(), row.get().schema(),
                    runtime.engine().identifierMax())) {
                return java.util.OptionalLong.empty();
            }
            return java.util.OptionalLong.of(runtime.engine().schemaSize(c, row.get().schema()));
        }
    }
}
