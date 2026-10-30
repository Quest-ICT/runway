package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import mx.quest.database.spi.DatabaseException;
import mx.quest.database.spi.DatabaseUnavailableException;
import mx.quest.database.spi.PluginDatabase;
import mx.quest.database.spi.ProviderSettings;
import mx.quest.database.spi.Scope;
import mx.quest.database.spi.ScopeNotReadyException;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The database plugin against real PostgreSQL and MariaDB servers (see {@link Harness} for how they are named).
 * Every case runs on both products; each test uses its own namespace and drops it afterwards.
 */
class RealServerTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @TempDir
    Path work;

    private final List<Harness> open = new ArrayList<>();
    private final List<String[]> namespaces = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (Harness h : open) {
            h.close();
        }
        for (String[] ns : namespaces) {
            Harness.dropNamespace(ns[0], Harness.endpoint(ns[0]).orElseThrow(), ns[1], work);
        }
    }

    private String namespace(String engine) {
        String ns = "it" + (engine.equals("postgresql") ? "p" : "m") + ProcessHandle.current().pid() % 10000
                + SEQ.incrementAndGet();
        namespaces.add(new String[] {engine, ns});
        return ns;
    }

    private Harness node(String engine) throws Exception {
        Harness.Endpoint endpoint = Harness.endpoint(engine).orElse(null);
        assumeTrue(endpoint != null, "no " + engine + " server configured (-Ddb.it." + engine + "=host:port)");
        Harness h = new Harness(engine, namespace(engine), endpoint, work.resolve("n" + SEQ.incrementAndGet()));
        open.add(h);
        return h.start();
    }

    private Harness secondNode(Harness first) throws Exception {
        Harness h = new Harness(first.engine, first.namespace, first.endpoint, work.resolve("n" + SEQ.incrementAndGet()),
                first.sites);
        open.add(h);
        return h.start();
    }

    // --- the guest book plugin used throughout ---------------------------------------------------------------

    static Map<String, String> guestScripts(int versions) {
        Map<String, String> m = new HashMap<>();
        String pg = "db/guest/postgresql/site/";
        String my = "db/guest/mariadb/site/";
        m.put(pg + "V1__init.sql", """
                CREATE SEQUENCE guest__entry_seq
                -- runway:next
                CREATE TABLE guest__entry (id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL, body TEXT,
                  amount NUMERIC(10,2), flag BOOLEAN NOT NULL DEFAULT false, data BYTEA,
                  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ)
                -- runway:next
                CREATE FUNCTION guest__touch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.updated_at := now(); RETURN NEW; END $$
                -- runway:next
                CREATE TRIGGER guest__entry_touch BEFORE INSERT OR UPDATE ON guest__entry
                  FOR EACH ROW EXECUTE FUNCTION guest__touch()
                """);
        m.put(my + "V1__init.sql", """
                CREATE SEQUENCE guest__entry_seq
                -- runway:next
                CREATE TABLE guest__entry (id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL, body TEXT,
                  amount DECIMAL(10,2), flag BOOLEAN NOT NULL DEFAULT 0, data LONGBLOB,
                  created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NULL) ENGINE=InnoDB
                -- runway:next
                CREATE TRIGGER guest__entry_touch BEFORE INSERT ON guest__entry
                  FOR EACH ROW SET NEW.updated_at = UTC_TIMESTAMP(6)
                """);
        m.put(pg + "V2__note.sql", "ALTER TABLE guest__entry ADD COLUMN note VARCHAR(40)\n");
        m.put(my + "V2__note.sql", "ALTER TABLE guest__entry ADD COLUMN note VARCHAR(40)\n");
        String index = versions >= 2 ? "V1__init.sql\nV2__note.sql\n" : "V1__init.sql\n";
        m.put(pg + "index", index);
        m.put(my + "index", index);
        return m;
    }

    static long insert(PluginDatabase db, Scope scope, String name) {
        return scope.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO guest__entry (id, name, body, amount, flag, "
                    + "data, created_at) VALUES (" + db.dialect().nextValue("guest__entry_seq")
                    + ", ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))")) {
                ps.setString(1, name);
                ps.setString(2, "body of " + name + " ñ €");
                ps.setBigDecimal(3, new java.math.BigDecimal("12.34"));
                ps.setBoolean(4, true);
                ps.setBytes(5, new byte[] {0, 1, 2, (byte) 255});
                ps.executeUpdate();
            }
            return db.dialect().lastValue(c, "guest__entry_seq");
        });
    }

    static List<String> names(Scope scope) {
        return scope.read(c -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT name FROM guest__entry ORDER BY id")) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    // --- availability -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void connectsAndTwoNodesSeeEachOther(String engine) throws Exception {
        Harness a = node(engine);
        assertThat(a.runtime.available()).isTrue();
        assertThat(a.runtime.health().server()).containsIgnoringCase(engine.equals("postgresql") ? "PostgreSQL"
                : "MariaDB");
        Harness b = secondNode(a);
        assertThat(b.runtime.available()).isTrue();
        assertThat(a.runtime.registry().nodes(Duration.ofMinutes(5))).hasSize(2);
        assertThat(a.schemasInNamespace()).containsExactly(a.namespace + "_app");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aWrongPasswordNeverStartsAndIsNeverLogged(String engine) throws Exception {
        Harness.Endpoint endpoint = Harness.endpoint(engine).orElse(null);
        assumeTrue(endpoint != null, "no " + engine + " server configured (-Ddb.it." + engine + "=host:port)");
        Harness h = new Harness(engine, namespace(engine), endpoint, work.resolve("bad"));
        open.add(h);
        String wrong = "Wrong-" + System.nanoTime();
        h.password = wrong;
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            h.runtime.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (h.runtime.health().lastError() == null && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(h.runtime.available()).isFalse();
            assertThat(h.runtime.status()).isEqualTo(DatabaseRuntime.Status.CONNECTING);
            // The saved-settings test reads what the provider's page holds now, never the running settings.
            ConnectionTester tester = new ConnectionTester(h.runtime, h.props, () -> h.providers, work);
            assertThat(tester.testable()).containsExactly(engine);
            h.saved = saved(endpoint, Harness.DATABASE, endpoint.port(), Harness.USER, wrong);
            assertThat(tester.saved(engine).category()).isEqualTo(ConnectionTester.Category.AUTHENTICATION);
            h.saved = saved(endpoint, Harness.DATABASE, endpoint.port(), Harness.USER, endpoint.password());
            ConnectionTester.Result ok = tester.saved(engine);
            assertThat(ok.category()).isEqualTo(ConnectionTester.Category.OK);
            assertThat(ok.detail()).containsIgnoringCase(engine.equals("postgresql") ? "PostgreSQL" : "MariaDB");
            h.saved = saved(endpoint, "no_such_db_" + SEQ.incrementAndGet(), endpoint.port(), Harness.USER,
                    endpoint.password());
            assertThat(tester.saved(engine).category()).isEqualTo(ConnectionTester.Category.DATABASE_MISSING);
            h.saved = saved(endpoint, Harness.DATABASE, 1, Harness.USER, endpoint.password());
            assertThat(tester.saved(engine).category()).isEqualTo(ConnectionTester.Category.UNREACHABLE);
            h.saved = saved(endpoint, Harness.DATABASE, 99999, Harness.USER, endpoint.password());
            assertThat(tester.saved(engine)).isEqualTo(new ConnectionTester.Result(
                    ConnectionTester.Category.INVALID_SETTINGS, "database-" + engine + " settings: the port must be "
                    + "between 1 and 65535, or blank for the default"));
            String other = engine.equals("postgresql") ? "mariadb" : "postgresql";
            assertThat(tester.saved(other).category()).isEqualTo(ConnectionTester.Category.NO_PROVIDER);
            assertThat(h.runtime.status()).as("a test changes nothing").isEqualTo(DatabaseRuntime.Status.CONNECTING);
        } finally {
            root.detachAppender(logs);
        }
        assertThat(logs.list).isNotEmpty();
        for (var event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(wrong).doesNotContain(endpoint.password());
        }
    }

    private static ProviderSettings saved(Harness.Endpoint endpoint, String database, int port, String user,
                                          String password) {
        return new ProviderSettings(endpoint.host(), String.valueOf(port), database, user, password, "disable", null,
                null);
    }

    @Test
    void withoutSettingsOrProviderNothingConnects() throws Exception {
        Harness.Endpoint endpoint = Harness.endpoint("postgresql").or(() -> Harness.endpoint("mariadb")).orElse(null);
        assumeTrue(endpoint != null, "no database server configured (-Ddb.it.postgresql or -Ddb.it.mariadb)");
        Harness h = new Harness("postgresql", namespace("postgresql"), endpoint, work.resolve("nc"));
        open.add(h);
        h.host = null;
        h.username = null;
        h.start();
        assertThat(h.runtime.status()).isEqualTo(DatabaseRuntime.Status.NOT_CONFIGURED);
        Harness p = new Harness("postgresql", namespace("postgresql"), endpoint, work.resolve("np"));
        open.add(p);
        p.providers.clear();
        p.start();
        assertThat(p.runtime.status()).isEqualTo(DatabaseRuntime.Status.NO_PROVIDER);
        assertThat(p.runtime.health().detail()).contains("database-postgresql");
    }

    // --- sites and scopes ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void siteIdsThatFoldAlikeKeepTheirDataApart(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        List<String> ids = List.of("acme.com", "acme_com", "a.b", "a_b", "A-B", "a-b", "Shop", "shop",
                "s".repeat(70));
        for (String id : ids) {
            h.sites.add(id);
        }
        Set<Long> firstIds = new HashSet<>();
        for (String id : ids) {
            firstIds.add(insert(db, db.siteInBackground(id), "first of " + id));
        }
        assertThat(firstIds).containsOnly(1L);
        for (String id : ids) {
            assertThat(names(db.site(new SiteRef(id)))).containsExactly("first of " + id);
        }
        assertThat(h.schemasInNamespace()).hasSize(ids.size() + 1);
        h.sites.current = "Shop";
        assertThat(names(db.currentSite())).containsExactly("first of Shop");
        h.sites.current = null;
        assertThatThrownBy(db::currentSite).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.siteInBackground("nobody")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void readRefusesWritesAndNestingFollowsTheRules(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("one");
        h.sites.add("two");
        Scope one = db.siteInBackground("one");
        insert(db, one, "x");
        assertThatThrownBy(() -> one.read(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("UPDATE guest__entry SET name = 'y'");
            }
        })).isInstanceOf(DatabaseException.class);
        assertThat(names(one)).containsExactly("x");
        // Same scope joins: one connection, and a throw rolls back the outer call.
        assertThatThrownBy(() -> one.transaction(c -> {
            insert(db, one, "inner");
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(names(one)).containsExactly("x");
        List<String> joined = one.transaction(c -> names(one));
        assertThat(joined).containsExactly("x");
        assertThatThrownBy(() -> one.read(c -> insert(db, one, "w"))).isInstanceOf(IllegalStateException.class);
        Scope two = db.siteInBackground("two");
        assertThatThrownBy(() -> one.transaction(c -> names(two))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another scope");
        assertThatThrownBy(() -> db.app().read(c -> 1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not declare");
    }

    @Test
    void postgresTemporaryTablesDoNotLeakToTheNextBorrower() throws Exception {
        Harness h = node("postgresql");
        h.props.setPoolMax(2);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("one");
        h.sites.add("two");
        db.siteInBackground("one").transaction(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE guest__scratch (a int)");
            }
            return null;
        });
        for (int i = 0; i < 5; i++) {
            boolean seen = db.siteInBackground("two").read(c -> {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_class WHERE relname = 'guest__scratch' "
                             + "AND relpersistence = 't'")) {
                    rs.next();
                    return rs.getInt(1) > 0;
                }
            });
            assertThat(seen).isFalse();
        }
    }

    // --- migrations ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aScriptCreatingAnObjectOutsideThePrefixIsRefusedAndLeavesNothing(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/bad/" + engine + "/site/index", "V1__init.sql\n");
        m.put("db/bad/" + engine + "/site/V1__init.sql",
                "CREATE TABLE bad__ok (a INT)\n-- runway:next\nCREATE TABLE stranger (a INT)\n");
        PluginDatabase db = h.consumer("bad", Set.of(Scope.Kind.SITE), m);
        h.sites.add("s");
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1))
                .isInstanceOfSatisfying(ScopeNotReadyException.class,
                        e -> assertThat(e.reason()).isEqualTo(ScopeNotReadyException.Reason.MIGRATION_FAILED))
                .hasMessageContaining("stranger");
        String schema = h.schemas.resolve("s");
        try (Connection c = h.raw()) {
            Set<String> names = new HashSet<>();
            for (Engine.DbObject o : h.runtime.engine().ownedObjects(c, schema)) {
                names.add(o.name());
            }
            assertThat(names).doesNotContain("stranger");
            if (engine.equals("postgresql")) {
                assertThat(names).doesNotContain("bad__ok"); // the whole script rolled back
            }
        }
        assertThat(h.runtime.registry().issues()).extracting(Registry.Issue::pluginId).containsExactly("bad");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aScriptThatSwitchesSchemaIsRefusedBeforeAnythingRuns(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/sneaky/" + engine + "/site/index", "V1__init.sql\n");
        m.put("db/sneaky/" + engine + "/site/V1__init.sql",
                "CREATE TABLE sneaky__t (a INT)\n-- runway:next\n" + (engine.equals("postgresql")
                        ? "SET search_path = public" : "USE runway") + "\n");
        PluginDatabase db = h.consumer("sneaky", Set.of(Scope.Kind.SITE), m);
        h.sites.add("s");
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1))
                .isInstanceOfSatisfying(ScopeNotReadyException.class,
                        e -> assertThat(e.reason()).isEqualTo(ScopeNotReadyException.Reason.PLUGIN_REFUSED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aFailedScriptWaitsForTheOperatorThenResumes(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/flaky/" + engine + "/site/index", "V1__init.sql\n");
        m.put("db/flaky/" + engine + "/site/V1__init.sql", """
                CREATE TABLE flaky__a (a INT)
                -- runway:next
                INSERT INTO flaky__dep (a) VALUES (1)
                -- runway:next
                CREATE TABLE flaky__b (a INT)
                """);
        PluginDatabase db = h.consumer("flaky", Set.of(Scope.Kind.SITE), m);
        h.sites.add("s");
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1))
                .isInstanceOfSatisfying(ScopeNotReadyException.class,
                        e -> assertThat(e.reason()).isEqualTo(ScopeNotReadyException.Reason.MIGRATION_FAILED));
        String schema = h.schemas.resolve("s");
        // Calls keep failing until an operator acts.
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1)).isInstanceOf(ScopeNotReadyException.class);
        try (Connection c = h.raw(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE " + h.runtime.engine().quote(schema) + ".flaky__dep (a INT)");
        }
        h.migrator.recover(h.registered("flaky"), Scope.Kind.SITE, schema, Migrator.Recovery.RETRY);
        // MariaDB resumes at statement 2: re-running statement 1 would fail on the existing flaky__a.
        int count = db.siteInBackground("s").read(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM flaky__b")) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertThat(count).isZero();
        assertThat(h.runtime.registry().issues()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aBusySetupPoolIsNotAnOutage(String engine) throws Exception {
        Harness h = node(engine);
        h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        // A long restore and a migration hold both setup connections.
        try (Connection one = h.runtime.setupConnection(); Connection two = h.runtime.setupConnection()) {
            long start = System.nanoTime();
            h.runtime.heartbeat();
            assertThat(h.runtime.available()).isTrue();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(10));
        }
        try (Connection c = h.raw(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + h.runtime.engine().quote(h.namespace + "_app")
                     + ".rw_nodes")) {
            rs.next();
            assertThat(rs.getInt(1)).as("the heartbeat was recorded").isPositive();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void theHeartbeatForgetsNodesNotSeenForADay(String engine) throws Exception {
        Harness h = node(engine);
        Engine e = h.runtime.engine();
        String nodes = e.quote(h.runtime.registry().appSchema()) + ".rw_nodes";
        try (Connection c = h.raw(); Statement st = c.createStatement()) {
            String now = e.nowSql();
            st.execute("INSERT INTO " + nodes + " (node_id, host, version, started_at, seen_at) VALUES "
                    + "('gone', 'h', 'v', " + now + " - INTERVAL '3' DAY, " + now + " - INTERVAL '2' DAY), "
                    + "('recent', 'h', 'v', " + now + " - INTERVAL '2' DAY, " + now + " - INTERVAL '1' HOUR)");
        }
        h.runtime.heartbeat();
        List<String> left = new ArrayList<>();
        try (Connection c = h.raw(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT node_id FROM " + nodes + " ORDER BY node_id")) {
            while (rs.next()) {
                left.add(rs.getString(1));
            }
        }
        assertThat(left).as("a node gone for two days is forgotten; one seen an hour ago and this one stay")
                .doesNotContain("gone").contains("recent").hasSize(2);
    }

    @Test
    void onMariaDbEverySessionHasExplicitTimestampDefaultsWhateverTheServerSays() throws Exception {
        Harness.Endpoint endpoint = Harness.endpoint("mariadb").orElse(null);
        assumeTrue(endpoint != null, "no mariadb server configured (-Ddb.it.mariadb=host:port)");
        Harness h = node("mariadb");
        // A server before 10.10 starts sessions with the variable off, where `created_at TIMESTAMP(6) NOT NULL`
        // becomes DEFAULT current_timestamp(6) ON UPDATE current_timestamp(6) (checked on 11.4 with it off).
        try (Connection c = h.raw(); Statement s = c.createStatement()) {
            s.execute("SET SESSION explicit_defaults_for_timestamp = 0");
            s.execute(h.runtime.engine().connectionInitSql(Duration.ofSeconds(30), null));
            try (ResultSet rs = s.executeQuery("SELECT @@SESSION.explicit_defaults_for_timestamp")) {
                rs.next();
                assertThat(rs.getInt(1)).as("the pools' session setup turns it on").isEqualTo(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aFullPoolFailsTheCallButIsNotAnOutage(String engine) throws Exception {
        Harness h = node(engine);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < h.runtime.config().poolMax(); i++) {
                held.add(h.runtime.borrow());
            }
            assertThatThrownBy(h.runtime::borrow).isInstanceOf(DatabaseUnavailableException.class)
                    .hasMessageContaining("too many database calls");
            assertThat(h.runtime.available()).isTrue(); // every other site and plugin keeps working
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        h.runtime.borrow().close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void markApplyingAFailedBatchNeverLeavesAnEarlierScriptOfItUnrun(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/batch/" + engine + "/site/index", "V1__a.sql\nV2__b.sql\n");
        m.put("db/batch/" + engine + "/site/V1__a.sql", "CREATE TABLE batch__a (a INT)\n");
        m.put("db/batch/" + engine + "/site/V2__b.sql", "INSERT INTO batch__dep (a) VALUES (1)\n");
        PluginDatabase db = h.consumer("batch", Set.of(Scope.Kind.SITE), m);
        h.sites.add("s");
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1)).isInstanceOf(ScopeNotReadyException.class);
        String schema = h.schemas.resolve("s");
        // The operator creates what V2 needs by hand and marks the failure applied (V2's insert, done by hand too;
        // on PostgreSQL V1, rolled back with it, as well).
        try (Connection c = h.raw(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + h.runtime.engine().quote(schema) + ".batch__dep (a INT)");
            if (engine.equals("postgresql")) {
                st.execute("CREATE TABLE " + h.runtime.engine().quote(schema) + ".batch__a (a INT)");
            }
        }
        h.migrator.recover(h.registered("batch"), Scope.Kind.SITE, schema, Migrator.Recovery.MARK_APPLIED);
        // On PostgreSQL V1 was rolled back with V2: it has a failed row of its own, so "mark applied" covers it (the
        // operator was told both were rolled back) instead of leaving V1 below the highest applied version with no
        // row, skipped for ever. On MariaDB V1 really ran (no transactional DDL) and has its own applied row.
        int one = db.siteInBackground("s").read(c -> 1);
        assertThat(one).isEqualTo(1);
        if (engine.equals("postgresql")) {
            try (Connection c = h.raw(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version, state FROM " + h.runtime.engine().quote(schema)
                         + ".rw_migrations WHERE plugin_id = 'batch' ORDER BY version")) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getInt(1) + ":" + rs.getString(2));
                }
                assertThat(rows).containsExactly("1:applied", "2:applied"); // both rolled back, both marked
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aFailedBatchKeepsNamingTheScriptThatFailedAndItsErrorWhenCheckedAgain(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/batch/" + engine + "/site/index", "V1__a.sql\nV2__b.sql\n");
        m.put("db/batch/" + engine + "/site/V1__a.sql", "CREATE TABLE batch__a (a INT)\n");
        m.put("db/batch/" + engine + "/site/V2__b.sql", "INSERT INTO batch__dep (a) VALUES (1)\n");
        PluginDatabase db = h.consumer("batch", Set.of(Scope.Kind.SITE), m);
        h.sites.add("s");
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1)).isInstanceOf(ScopeNotReadyException.class);
        String first = h.runtime.registry().issues().get(0).detail();
        assertThat(first).contains("V2__b.sql").containsIgnoringCase("batch__dep");

        h.migrator.forget(h.schemas.resolve("s")); // what a later check (another node, the 30 s retry) does
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1))
                .isInstanceOf(ScopeNotReadyException.class).hasMessage(first);
        assertThat(h.runtime.registry().issues()).singleElement().satisfies(i -> {
            assertThat(i.detail()).isEqualTo(first);
            assertThat(i.version()).isEqualTo(2);
        });
        if (engine.equals("postgresql")) {
            assertThat(first).contains("V1__a.sql"); // rolled back with it, and to run again
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void markAppliedCoversAScriptBelowTheHighestThatNeverRan(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/gap/" + engine + "/site/index", "V1__a.sql\nV2__b.sql\n");
        m.put("db/gap/" + engine + "/site/V1__a.sql", "CREATE TABLE gap__a (a INT)\n");
        m.put("db/gap/" + engine + "/site/V2__b.sql", "CREATE TABLE gap__b (a INT)\n");
        PluginDatabase db = h.consumer("gap", Set.of(Scope.Kind.SITE), m);
        h.sites.add("s");
        db.siteInBackground("s").read(c -> 1);
        String schema = h.schemas.resolve("s");
        try (Connection c = h.raw(); Statement st = c.createStatement()) { // V1's row is lost: a gap below V2
            st.execute("DELETE FROM " + h.runtime.engine().quote(schema) + ".rw_migrations WHERE plugin_id = 'gap' "
                    + "AND version = 1");
        }
        h.migrator.forget(schema);
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1)).isInstanceOf(ScopeNotReadyException.class)
                .hasMessageContaining("Mark applied");
        h.migrator.recover(h.registered("gap"), Scope.Kind.SITE, schema, Migrator.Recovery.MARK_APPLIED);
        int one = db.siteInBackground("s").read(c -> 1);
        assertThat(one).isEqualTo(1);
        assertThat(h.runtime.registry().issues()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql"})
    void retryingAFailedBatchRunsEveryScriptOfItAgain(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/batch/" + engine + "/site/index", "V1__a.sql\nV2__b.sql\n");
        m.put("db/batch/" + engine + "/site/V1__a.sql", "CREATE TABLE batch__a (a INT)\n");
        m.put("db/batch/" + engine + "/site/V2__b.sql", "INSERT INTO batch__dep (a) VALUES (1)\n");
        PluginDatabase db = h.consumer("batch", Set.of(Scope.Kind.SITE), m);
        h.sites.add("s");
        assertThatThrownBy(() -> db.siteInBackground("s").read(c -> 1)).isInstanceOf(ScopeNotReadyException.class);
        assertThat(h.runtime.registry().issues()).anySatisfy(i -> assertThat(i.detail()).contains("V1__a.sql"));
        String schema = h.schemas.resolve("s");
        try (Connection c = h.raw(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + h.runtime.engine().quote(schema) + ".batch__dep (a INT)");
        }
        h.migrator.recover(h.registered("batch"), Scope.Kind.SITE, schema, Migrator.Recovery.RETRY);
        int count = db.siteInBackground("s").read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM batch__a")) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertThat(count).isZero();
        assertThat(h.runtime.registry().issues()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aChangedScriptAndADowngradeAreRefusedUntilHandled(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "a");
        String schema = h.schemas.resolve("s");
        // A second node ships a changed V1.
        Harness changed = secondNode(h);
        Map<String, String> edited = guestScripts(2);
        String key = "db/guest/" + engine + "/site/V1__init.sql";
        edited.put(key, edited.get(key) + "-- edited\n");
        PluginDatabase db2 = changed.consumer("guest", Set.of(Scope.Kind.SITE), edited);
        assertThatThrownBy(() -> names(db2.siteInBackground("s")))
                .isInstanceOfSatisfying(ScopeNotReadyException.class,
                        e -> assertThat(e.reason()).isEqualTo(ScopeNotReadyException.Reason.CHECKSUM_MISMATCH));
        changed.migrator.recover(changed.registered("guest"), Scope.Kind.SITE, schema,
                Migrator.Recovery.ACCEPT_CHECKSUM);
        assertThat(names(db2.siteInBackground("s"))).containsExactly("a");
        // A third node ships only V1: the schema is newer than it.
        Harness older = secondNode(h);
        PluginDatabase db3 = older.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        assertThatThrownBy(() -> names(db3.siteInBackground("s")))
                .isInstanceOfSatisfying(ScopeNotReadyException.class,
                        e -> assertThat(e.reason()).isEqualTo(ScopeNotReadyException.Reason.NEWER_THAN_INSTALLED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void prefixesThatCouldOwnTheSameNamesAreRefused(String engine) throws Exception {
        Harness h = node(engine);
        h.sites.add("s");
        PluginDatabase first = h.consumer("a-b", Set.of(Scope.Kind.SITE), Map.of());
        int one = first.siteInBackground("s").read(c -> 1);
        assertThat(one).isEqualTo(1);
        PluginDatabase second = h.consumer("a_b", Set.of(Scope.Kind.SITE), Map.of());
        assertThatThrownBy(() -> second.siteInBackground("s").read(c -> 1))
                .isInstanceOfSatisfying(ScopeNotReadyException.class,
                        e -> assertThat(e.reason()).isEqualTo(ScopeNotReadyException.Reason.PLUGIN_REFUSED))
                .hasMessageContaining("clashes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void theAppScopeHoldsDataThatBelongsToNoSite(String engine) throws Exception {
        Harness h = node(engine);
        Map<String, String> m = new HashMap<>();
        m.put("db/stats/" + engine + "/app/index", "V1__init.sql\n");
        m.put("db/stats/" + engine + "/app/V1__init.sql", "CREATE TABLE stats__hit (k VARCHAR(20) PRIMARY KEY, n INT)\n");
        PluginDatabase db = h.consumer("stats", Set.of(Scope.Kind.APP), m);
        db.app().transaction(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("INSERT INTO stats__hit (k, n) VALUES ('x', 1)");
            }
        });
        assertThat(ScreenQueries.appSchemaPlugins(h.runtime)).containsExactly("stats");
    }

    // --- lifecycle ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aDeletedSiteLosesItsDataAndARecreatedOneStartsEmpty(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        String before = h.schemas.resolve("s");
        Harness other = secondNode(h);
        PluginDatabase dbOther = other.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        assertThat(names(dbOther.siteInBackground("s"))).containsExactly("old"); // cached on the other node
        h.sites.delete("s");
        h.schemas.dropDeleted("s");
        assertThat(h.schemasInNamespace()).doesNotContain(before);
        h.sites.add("s");
        assertThat(names(db.siteInBackground("s"))).isEmpty();
        assertThat(names(dbOther.siteInBackground("s"))).isEmpty(); // the other node's cache did not bind it
        assertThat(h.schemas.resolve("s")).isNotEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aSiteRecreatedWhileTheDatabaseWasDownNeverInheritsTheOldData(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        DatabaseSiteEvents events = new DatabaseSiteEvents(h.core, Runnable::run);
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        String before = h.schemas.resolve("s");
        h.runtime.markOutage(new java.sql.SQLException("test outage"));
        events.onSiteLifecycle(new SiteLifecycleEvent("s", SiteLifecycleEvent.Kind.DELETED, false, true));
        h.sites.delete("s"); // the removal could not reach the database
        h.sites.add("s");
        events.onSiteLifecycle(new SiteLifecycleEvent("s", SiteLifecycleEvent.Kind.CREATED, false, true));
        assertThat(h.files.readText("s", SiteSchemas.TOKEN_FILE)).get().asString().contains(SiteSchemas.FRESH_LINE);

        h.runtime.heartbeat(); // the database is back
        h.schemas.evictAll();
        assertThat(names(db.siteInBackground("s"))).isEmpty();
        assertRetired(h, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aNewSiteUnderAnOldIdHasTheEarlierSitesDataRetiredWhenItIsCreated(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        String before = h.schemas.resolve("s");
        h.sites.delete("s"); // deleted while the plugin was off: no event, no removal
        h.sites.add("s");
        new DatabaseSiteEvents(h.core, Runnable::run)
                .onSiteLifecycle(new SiteLifecycleEvent("s", SiteLifecycleEvent.Kind.CREATED, false, true));
        assertRetired(h, before);
        h.schemas.evictAll();
        assertThat(names(db.siteInBackground("s"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aSiteWithoutATokenIsRefusedUntilTheSweepHasSeenItTwiceThenStartsEmpty(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        String before = h.schemas.resolve("s");
        h.sites.delete("s"); // deleted and re-created while the plugin was off: no events at all
        h.sites.add("s");
        h.schemas.evictAll();

        // Neither the old data (it may be another site's) nor a fresh start (a restore may be writing the token back).
        assertThatThrownBy(() -> names(db.siteInBackground("s"))).isInstanceOf(ScopeNotReadyException.class);
        h.schemas.reconcile();
        assertMapped(h, "s", before); // the first pass only notes it
        h.schemas.reconcile(); // still no token on the next pass
        assertRetired(h, before);
        assertThat(names(db.siteInBackground("s"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aSiteRecreatedFromAnotherSitesBackupNeverBindsTheDeletedSitesData(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("a");
        h.sites.add("s");
        insert(db, db.siteInBackground("a"), "a's");
        insert(db, db.siteInBackground("s"), "old");
        String before = h.schemas.resolve("s");
        String aSchema = h.schemas.resolve("a");
        String aToken = h.files.readText("a", SiteSchemas.TOKEN_FILE).orElseThrow();
        assertThat(aToken).contains("site: a");
        h.sites.delete("s"); // the plugin was off: no removal
        h.sites.add("s");
        h.files.writeText("s", SiteSchemas.TOKEN_FILE, aToken); // the kernel copied a's folder into the new site
        assertThat(h.schemas.folder("s").state()).isEqualTo(SiteSchemas.FolderState.FOREIGN);
        h.schemas.evictAll();

        h.schemas.reconcile();
        assertRetired(h, before);
        assertThat(names(db.siteInBackground("s"))).isEmpty();
        assertThat(h.schemas.resolve("a")).isEqualTo(aSchema);
        assertThat(names(db.siteInBackground("a"))).containsExactly("a's");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aSiteFolderThatCannotBeReadKeepsItsDataEvenWhenTheSiteReadsAsMissing(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        h.sites.add("gone");
        insert(db, db.siteInBackground("s"), "live");
        insert(db, db.siteInBackground("gone"), "x");
        String live = h.schemas.resolve("s");
        String goneSchema = h.schemas.resolve("gone");
        h.sites.delete("gone");
        Path dir = h.sites.root.resolve("s");
        var perms = Files.getPosixFilePermissions(dir);
        h.sites.hidden.add("s"); // a kernel that answers "no such site" when it cannot read the site's folder
        Files.setPosixFilePermissions(dir, Set.of());
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(dir.resolve("plugins")),
                    "running as a user that ignores permissions");
            h.schemas.reconcile();
            h.schemas.dropDeleted("s");
            h.sites.throwing.add("s"); // a kernel that says it cannot tell
            h.schemas.reconcile();
        } finally {
            Files.setPosixFilePermissions(dir, perms);
            h.sites.hidden.clear();
            h.sites.throwing.clear();
        }
        assertThat(h.schemasInNamespace()).contains(live);
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
        assertThat(names(db.siteInBackground("s"))).containsExactly("live");
        // The sweep went on past it: the site that is really gone had its data retired (dropped after the grace).
        assertRetired(h, goneSchema);
        assertThat(h.schemasInNamespace()).contains(goneSchema);
    }

    private static void assertMapped(Harness h, String siteId, String schema) throws Exception {
        try (Connection c = h.raw()) {
            assertThat(h.runtime.registry().site(c, siteId)).get().satisfies(r -> {
                assertThat(r.schema()).isEqualTo(schema);
                assertThat(r.state()).isEqualTo(SiteSchemas.LIVE);
            });
        }
    }

    private static void assertRetired(Harness h, String schema) throws Exception {
        try (Connection c = h.raw()) {
            assertThat(h.runtime.registry().schemas(c).rows()).anySatisfy(r -> {
                assertThat(r.schema()).isEqualTo(schema);
                assertThat(r.state()).isEqualTo(SiteSchemas.RETIRED);
            });
            assertThat(h.runtime.registry().sites(c)).noneSatisfy(r -> assertThat(r.schema()).isEqualTo(schema));
        }
    }

    // --- backups ------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aBackupRestoresIntoANewGenerationWithItsValuesSequencesAndTimestamps(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        h.sites.add("s");
        Scope s = db.siteInBackground("s");
        insert(db, s, "one");
        insert(db, s, "two");
        List<String> snapshot = rows(s);
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        assertThat(archive.order.get(archive.order.size() - 1)).isEqualTo(Archive.MANIFEST);
        insert(db, s, "after-backup");
        String before = h.schemas.resolve("s");
        Thread.sleep(20); // a trigger that ran again would give a later updated_at
        h.backup.importInto(archive, new SiteRef("s"));
        assertThat(h.schemas.resolve("s")).isNotEqualTo(before);
        assertThat(rows(db.siteInBackground("s"))).isEqualTo(snapshot);
        assertThat(insert(db, db.siteInBackground("s"), "three")).isGreaterThan(2);
        assertThat(h.backup.outcome("s")).get().extracting(DatabaseBackup.Outcome::ok).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aRestoreThatFailsWhileLoadingLeavesTheLiveDataAlone(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "live");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        // Rewrite the part with a name too long for its column, keeping the checksums consistent.
        String part = "tables/guest__entry/part-00001.jsonl.gz";
        long[] spent = {0};
        List<Object[]> rows = new ArrayList<>();
        List<Archive.Column> columns = Archive.read(entries(archive), 1 << 30, engine.equals("postgresql"))
                .tables("guest").get(0).columns();
        Archive.forEachRow(archive.entries.get(part), "guest__entry", columns, 1L << 32, spent, rows::add);
        rows.get(0)[1] = "x".repeat(500);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            List<Map<String, String>> header = new ArrayList<>();
            for (Archive.Column c : columns) {
                header.add(Map.of("name", c.name(), "type", c.type()));
            }
            gz.write(Archive.json().writeValueAsBytes(Map.of("columns", header)));
            gz.write('\n');
            for (Object[] r : rows) {
                gz.write(Archive.json().writeValueAsBytes(r));
                gz.write('\n');
            }
        }
        archive.entries.put(part, bytes.toByteArray());
        var index = (tools.jackson.databind.node.ObjectNode) Archive.parse(
                archive.entries.get("tables/guest__entry/index.json"), "index");
        ((tools.jackson.databind.node.ObjectNode) index.get("parts").get(0))
                .put("sha256", Names.sha256Hex(bytes.toByteArray()));
        archive.entries.put("tables/guest__entry/index.json", Archive.json().writeValueAsBytes(index));
        String before = h.schemas.resolve("s");
        assertThatThrownBy(() -> h.backup.importInto(archive, new SiteRef("s"))).isInstanceOf(java.io.IOException.class);
        assertThat(h.schemas.resolve("s")).isEqualTo(before);
        assertThat(names(db.siteInBackground("s"))).containsExactly("live");
        assertThat(h.backup.lastFailure()).isPresent();
        assertThat(h.schemasInNamespace()).hasSize(2); // the app schema and the live one: no staging left behind
        if (engine.equals("mariadb")) {
            // The load turned FOREIGN_KEY_CHECKS off for its session: that connection must not be lent again. Both
            // setup connections (the pool's maximum) are taken, so the one the load used would be among them.
            try (Connection a = h.runtime.setupConnection(); Connection b = h.runtime.setupConnection()) {
                for (Connection c : List.of(a, b)) {
                    try (Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT @@SESSION.FOREIGN_KEY_CHECKS")) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("foreign-key checks on a setup connection").isEqualTo(1);
                    }
                }
            }
        }
    }

    /**
     * What the kernel does before any participant imports: the archive's site folder replaces the live one, so the
     * token file is the archive's (or missing, for a backup taken before the site used the database).
     */
    private static void swapInArchivedFolder(Harness h, String siteId, Optional<String> archivedTokenFile)
            throws Exception {
        if (archivedTokenFile.isPresent()) {
            h.files.writeText(siteId, SiteSchemas.TOKEN_FILE, archivedTokenFile.get());
        } else {
            h.files.delete(siteId, SiteSchemas.TOKEN_FILE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aFailedRestoreOfAnOlderBackupKeepsTheLiveDataAfterTheOldFolderIsSwappedIn(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        Harness.MemoryArchive older = new Harness.MemoryArchive();
        h.backup.export(older, new SiteRef("s"));
        String olderFolder = h.files.readText("s", SiteSchemas.TOKEN_FILE).orElseThrow();
        h.backup.importInto(older, new SiteRef("s")); // a restore that worked: a newer generation, a new token
        insert(db, db.siteInBackground("s"), "live");
        String live = h.schemas.resolve("s");
        byte[] part = older.entries.get("tables/guest__entry/part-00001.jsonl.gz");
        part[part.length - 9] ^= 1; // the next restore of the same backup fails

        swapInArchivedFolder(h, "s", Optional.of(olderFolder));
        assertThatThrownBy(() -> h.backup.importInto(older, new SiteRef("s"))).hasMessageContaining("checksum");

        h.schemas.evictAll();
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
        h.schemas.reconcile();
        assertThat(names(db.siteInBackground("s"))).containsExactly("old", "live");
        try (Connection c = h.raw()) {
            String token = h.runtime.registry().site(c, "s").orElseThrow().token();
            assertThat(h.files.readText("s", SiteSchemas.TOKEN_FILE)).get().asString().contains(token);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aSweepOrARequestBetweenTheSwapAndTheImportKeepsTheLiveData(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        Harness.MemoryArchive older = new Harness.MemoryArchive();
        h.backup.export(older, new SiteRef("s"));
        String olderFolder = h.files.readText("s", SiteSchemas.TOKEN_FILE).orElseThrow();
        h.backup.importInto(older, new SiteRef("s"));
        insert(db, db.siteInBackground("s"), "live");
        String live = h.schemas.resolve("s");

        swapInArchivedFolder(h, "s", Optional.of(olderFolder)); // the import has not run yet
        h.schemas.reconcile();
        assertThat(h.schemas.resolve("s")).isEqualTo(live);

        swapInArchivedFolder(h, "s", Optional.of(olderFolder));
        h.schemas.evictAll(); // a node that had not cached the site
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
        assertThat(names(db.siteInBackground("s"))).containsExactly("old", "live");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aBackupFromBeforeTheSiteUsedTheDatabaseLeavesItsDataAlone(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "live");
        String live = h.schemas.resolve("s");

        swapInArchivedFolder(h, "s", Optional.empty());
        h.backup.importInto(new Harness.MemoryArchive(), new SiteRef("s")); // no database entries at all

        h.schemas.evictAll();
        h.schemas.reconcile();
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
        assertThat(names(db.siteInBackground("s"))).containsExactly("live");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aRestoreWhileTheDatabaseIsDownLeavesAMarkerThatKeepsTheLiveData(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "live");
        String live = h.schemas.resolve("s");

        h.runtime.markOutage(new java.sql.SQLException("test outage"));
        swapInArchivedFolder(h, "s", Optional.empty());
        h.backup.importInto(new Harness.MemoryArchive(), new SiteRef("s"));
        assertThat(h.files.readText("s", SiteSchemas.TOKEN_FILE)).get().asString().contains(SiteSchemas.ADOPT_LINE);

        h.runtime.heartbeat(); // the database is back
        h.schemas.evictAll();
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
        assertThat(names(db.siteInBackground("s"))).containsExactly("live");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aFolderThatCannotBeReadIsNeverTakenForARecreatedSite(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "live");
        String live = h.schemas.resolve("s");
        Path folder;
        try (var walk = Files.walk(h.sites.root.resolve("s"))) {
            folder = walk.filter(p -> p.getFileName().toString().equals(SiteSchemas.TOKEN_FILE)).findFirst()
                    .orElseThrow().getParent();
        }
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, Set.of());
        try {
            assumeFalse(Files.isReadable(folder.resolve(SiteSchemas.TOKEN_FILE)), "permissions are not enforced");
            h.schemas.evictAll();
            assertThatThrownBy(() -> h.schemas.resolve("s")).isInstanceOf(DatabaseUnavailableException.class);
            h.schemas.reconcile();
            // A backup must not record "no data" for a site whose folder it cannot read.
            assertThatThrownBy(() -> h.backup.export(new Harness.MemoryArchive(), new SiteRef("s")))
                    .isInstanceOf(java.io.IOException.class);
        } finally {
            Files.setPosixFilePermissions(folder, before);
        }
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
        assertThat(names(db.siteInBackground("s"))).containsExactly("live");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void theSweepDecidesAgainUnderTheLockAndNeverDropsASchemaThatJustWentLive(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        // A restore builds a staging generation; the sweep's snapshot is taken now; then the restore switches to it.
        String staging = h.schemas.createStaging("s");
        Registry.Schemas snapshot;
        try (Connection c = h.raw()) {
            snapshot = h.runtime.registry().schemas(c);
        }
        h.schemas.switchTo("s", staging);
        Registry.SchemaRow stale = snapshot.rows().stream().filter(r -> r.schema().equals(staging)).findFirst()
                .orElseThrow();
        assertThat(SiteSchemas.droppable(stale, Set.of(), snapshot.now())).isTrue(); // what the snapshot says

        h.schemas.dropIfStillDroppable("s", staging);

        assertThat(h.schemasInNamespace()).contains(staging);
        assertThat(h.schemas.resolve("s")).isEqualTo(staging);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void theSweepActingOnAStaleSnapshotNeverDropsAStagingSchemaThatWentLive(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "old");
        String staging = h.schemas.createStaging("s");
        // The sweep reads the registry (staging, mapped by nobody); a restore then switches to it before the sweep
        // acts on what it read.
        h.schemas.afterSweepSnapshot = () -> {
            try {
                h.schemas.switchTo("s", staging);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        };
        h.schemas.reconcile();

        assertThat(h.schemasInNamespace()).contains(staging);
        assertThat(h.schemas.resolve("s")).isEqualTo(staging);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void abandoningAStagingSchemaThatASiteNowMapsLeavesItAlone(String engine) throws Exception {
        Harness h = node(engine);
        h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        String staging = h.schemas.createStaging("s");
        h.schemas.switchTo("s", staging); // committed, and then the restore saw an error
        h.schemas.abandonStaging(staging);

        assertThat(h.schemasInNamespace()).contains(staging);
        assertMapped(h, "s", staging);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aRestoredFolderWithoutATokenKeepsTheLiveDataUntilTheImportRuns(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "live");
        String live = h.schemas.resolve("s");

        // A backup from before the database plugin: the kernel has swapped the folder in, the import has not run.
        swapInArchivedFolder(h, "s", Optional.empty());
        h.schemas.reconcile();
        new DatabaseSiteEvents(h.core, Runnable::run)
                .onSiteLifecycle(new SiteLifecycleEvent("s", SiteLifecycleEvent.Kind.RESTORED, false, false));
        assertThat(h.schemas.resolve("s")).as("a node that had it cached keeps serving it").isEqualTo(live);
        h.schemas.evictAll();
        assertThatThrownBy(() -> h.schemas.resolve("s")).as("a node without it waits")
                .isInstanceOf(ScopeNotReadyException.class);
        assertMapped(h, "s", live);

        h.backup.importInto(new Harness.MemoryArchive(), new SiteRef("s"));
        h.schemas.reconcile();
        h.schemas.reconcile();
        h.schemas.evictAll();
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
        assertThat(names(db.siteInBackground("s"))).containsExactly("live");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aMalformedDatabaseStateStillLeavesTheFolderNamingTheLiveData(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "live");
        String live = h.schemas.resolve("s");
        swapInArchivedFolder(h, "s", Optional.empty());
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        try (var out = archive.put(Archive.STATE)) {
            out.write("{\"schema\": 7}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> h.backup.importInto(archive, new SiteRef("s"))).isInstanceOf(IOException.class);
        assertThat(h.schemas.folder("s").state()).isEqualTo(SiteSchemas.FolderState.TOKEN);
        h.schemas.evictAll();
        assertThat(h.schemas.resolve("s")).isEqualTo(live);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aTamperedArchiveIsRefusedBeforeAnythingIsTouched(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "live");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        byte[] part = archive.entries.get("tables/guest__entry/part-00001.jsonl.gz");
        part[part.length - 9] ^= 1;
        assertThatThrownBy(() -> h.backup.importInto(archive, new SiteRef("s"))).hasMessageContaining("checksum");
        assertThat(names(db.siteInBackground("s"))).containsExactly("live");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void dataOfAnAbsentPluginIsKeptUntilThePluginArrives(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "kept");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        // Another node without the guest plugin restores the backup.
        Harness without = secondNode(h);
        without.backup.importInto(archive, new SiteRef("s"));
        assertThat(without.files.list("s", "deferred/guest")).isNotEmpty();
        // The plugin arrives (on a fresh node) and its first call finds its data.
        Harness later = secondNode(h);
        PluginDatabase arrived = later.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        assertThat(names(arrived.siteInBackground("s"))).containsExactly("kept");
        assertThat(later.files.stat("s", "deferred/guest")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void parkedDataNewerThanTheArrivingPluginWaitsForOneThatCanReadIt(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "kept");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        Harness without = secondNode(h);
        without.backup.importInto(archive, new SiteRef("s")); // parked: the guest plugin is not there
        // An older guest plugin (one script; the data is at two) arrives: it must not start with empty tables.
        Harness older = secondNode(h);
        PluginDatabase old = older.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        assertThatThrownBy(() -> old.siteInBackground("s").read(c -> 1))
                .isInstanceOfSatisfying(ScopeNotReadyException.class, e -> assertThat(e.reason())
                        .isEqualTo(ScopeNotReadyException.Reason.NEWER_THAN_INSTALLED));
        assertThat(older.files.list("s", "deferred/guest")).isNotEmpty();
        assertThat(older.runtime.registry().issues()).anySatisfy(i -> {
            assertThat(i.pluginId()).isEqualTo("guest");
            assertThat(i.state()).isEqualTo(Migrator.ISSUE_NEWER);
            assertThat(i.detail()).contains("kept", "version 2");
        });
        // The right version arrives and finds the data.
        Harness later = secondNode(h);
        PluginDatabase arrived = later.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        assertThat(names(arrived.siteInBackground("s"))).containsExactly("kept");
        assertThat(later.runtime.registry().issues()).noneSatisfy(i -> assertThat(i.pluginId()).isEqualTo("guest"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void parkedDataThatCannotBeReadRefusesThePluginInsteadOfStartingItEmpty(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "kept");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        Harness without = secondNode(h);
        without.backup.importInto(archive, new SiteRef("s"));
        String part = without.files.list("s", "deferred/guest/tables/guest__entry").get(0).name();
        String path = "deferred/guest/tables/guest__entry/" + part;
        String good = without.files.readText("s", path).orElseThrow();
        without.files.writeText("s", path, "AAAA" + good.substring(4)); // damaged on disk

        Harness arrives = secondNode(h);
        PluginDatabase arrived = arrives.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        assertThatThrownBy(() -> names(arrived.siteInBackground("s")))
                .isInstanceOfSatisfying(ScopeNotReadyException.class, e -> assertThat(e.reason())
                        .isEqualTo(ScopeNotReadyException.Reason.PLUGIN_REFUSED));
        assertThat(arrives.runtime.registry().issues()).anySatisfy(i -> {
            assertThat(i.state()).isEqualTo(Migrator.ISSUE_PARKED);
            assertThat(i.detail()).contains("deferred/guest/");
        });
        assertThat(arrives.files.stat("s", "deferred/guest")).isPresent();

        without.files.writeText("s", path, good); // repaired
        Harness again = secondNode(h);
        PluginDatabase retried = again.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        assertThat(names(retried.siteInBackground("s"))).containsExactly("kept");
        assertThat(again.files.stat("s", "deferred/guest")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void parkedDataIsStillLoadedAfterAnAttemptThatMigratedButDidNotLoad(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "kept");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        Harness without = secondNode(h);
        without.backup.importInto(archive, new SiteRef("s"));
        // An attempt got as far as migrating the tables at the data's version, then failed before loading.
        String manifest = "deferred/guest/" + Archive.MANIFEST + ".b64";
        String kept = without.files.readText("s", manifest).orElseThrow();
        without.files.delete("s", manifest);
        Harness first = secondNode(h);
        assertThat(names(first.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1)).siteInBackground("s")))
                .isEmpty();
        without.files.writeText("s", manifest, kept);

        Harness next = secondNode(h);
        PluginDatabase arrived = next.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        assertThat(names(arrived.siteInBackground("s"))).containsExactly("kept");
        assertThat(next.files.stat("s", "deferred/guest")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void parkedDataOlderThanTheTablesAlreadyThereIsRefusedNotIgnored(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "kept");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        Harness without = secondNode(h);
        without.backup.importInto(archive, new SiteRef("s"));
        String manifest = "deferred/guest/" + Archive.MANIFEST + ".b64";
        String kept = without.files.readText("s", manifest).orElseThrow();
        without.files.delete("s", manifest);
        Harness first = secondNode(h);
        names(first.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2)).siteInBackground("s")); // at v2
        without.files.writeText("s", manifest, kept);

        Harness next = secondNode(h);
        PluginDatabase arrived = next.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        assertThatThrownBy(() -> names(arrived.siteInBackground("s")))
                .isInstanceOfSatisfying(ScopeNotReadyException.class, e -> assertThat(e.reason())
                        .isEqualTo(ScopeNotReadyException.Reason.PLUGIN_REFUSED));
        assertThat(next.runtime.registry().issues()).anySatisfy(i ->
                assertThat(i.detail()).contains("already at version 2"));
        assertThat(next.files.stat("s", "deferred/guest")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aSlowDownloadHoldsNoDatabaseConnection(String engine) throws Exception {
        Harness h = node(engine);
        PluginDatabase db = h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        insert(db, db.siteInBackground("s"), "one");
        // The kernel streams a download to the browser: a slow one blocks in put(). By then the export must have
        // given its connection back, or two slow downloads would hold the whole setup pool (two connections).
        List<Boolean> free = new ArrayList<>();
        Harness.MemoryArchive slow = new Harness.MemoryArchive() {
            @Override
            public java.io.OutputStream put(String entry) {
                if (free.isEmpty()) {
                    try (Connection a = h.runtime.setupConnection(); Connection b = h.runtime.setupConnection()) {
                        free.add(a.isValid(1) && b.isValid(1));
                    } catch (java.sql.SQLException e) {
                        free.add(false);
                    }
                }
                return super.put(entry);
            }
        };
        h.backup.export(slow, new SiteRef("s"));
        assertThat(free).containsExactly(true);
        assertThat(slow.order.get(slow.order.size() - 1)).isEqualTo(Archive.MANIFEST);
        h.backup.importInto(slow, new SiteRef("s"));
        assertThat(names(db.siteInBackground("s"))).containsExactly("one");
        try (var left = Files.list(h.runtime.workDir().resolve("spool"))) {
            assertThat(left).isEmpty(); // the spool is gone
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void aSiteWithoutDataBacksUpAsNone(String engine) throws Exception {
        Harness h = node(engine);
        h.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(1));
        h.sites.add("s");
        Harness.MemoryArchive archive = new Harness.MemoryArchive();
        h.backup.export(archive, new SiteRef("s"));
        assertThat(archive.entries.keySet()).containsExactly(Archive.STATE);
        assertThat(new String(archive.entries.get(Archive.STATE))).contains("none");
    }

    @Test
    void aBackupMovesBetweenProductsInBothDirections() throws Exception {
        Harness pg = node("postgresql");
        Harness my = node("mariadb");
        PluginDatabase dbPg = pg.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        PluginDatabase dbMy = my.consumer("guest", Set.of(Scope.Kind.SITE), guestScripts(2));
        pg.sites.add("s");
        my.sites.add("s");
        insert(dbPg, dbPg.siteInBackground("s"), "from postgres");
        Harness.MemoryArchive a = new Harness.MemoryArchive();
        pg.backup.export(a, new SiteRef("s"));
        my.backup.importInto(a, new SiteRef("s"));
        assertThat(rows(dbMy.siteInBackground("s"))).isEqualTo(rows(dbPg.siteInBackground("s")));
        insert(dbMy, dbMy.siteInBackground("s"), "from mariadb");
        Harness.MemoryArchive b = new Harness.MemoryArchive();
        my.backup.export(b, new SiteRef("s"));
        pg.backup.importInto(b, new SiteRef("s"));
        assertThat(rows(dbPg.siteInBackground("s"))).isEqualTo(rows(dbMy.siteInBackground("s")));
        assertThat(names(dbPg.siteInBackground("s"))).containsExactly("from postgres", "from mariadb");
    }

    /** Every row as text, the same on both products: the values a restore must keep. */
    static List<String> rows(Scope scope) {
        return scope.read(c -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, name, body, amount, flag, data, created_at, updated_at "
                         + "FROM guest__entry ORDER BY id")) {
                while (rs.next()) {
                    out.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                            + rs.getBigDecimal(4) + "|" + rs.getBoolean(5) + "|"
                            + java.util.HexFormat.of().formatHex(rs.getBytes(6)) + "|"
                            + Portable.read(rs, 7, "TIMESTAMPTZ", isPostgres(c)) + "|"
                            + Portable.read(rs, 8, "TIMESTAMPTZ", isPostgres(c)));
                }
            }
            return out;
        });
    }

    private static boolean isPostgres(Connection c) throws java.sql.SQLException {
        return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    }

    private static Archive.Entries entries(Harness.MemoryArchive archive) {
        return new Archive.Entries() {
            @Override
            public boolean has(String entry) {
                return archive.has(entry);
            }

            @Override
            public byte[] read(String entry) {
                return archive.entries.get(entry);
            }
        };
    }
}
