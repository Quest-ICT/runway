package mx.quest.demodatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import mx.quest.database.internal.DatabaseConfig;
import mx.quest.database.internal.DatabaseCore;
import mx.quest.database.internal.DatabaseProps;
import mx.quest.database.internal.DatabaseRuntime;
import mx.quest.database.internal.DatabasesImpl;
import mx.quest.database.internal.Engine;
import mx.quest.database.internal.Migrator;
import mx.quest.database.internal.ScriptSource;
import mx.quest.database.internal.SiteSchemas;
import mx.quest.database.mariadb.MariaDbProvider;
import mx.quest.database.postgresql.PostgresProvider;
import mx.quest.database.spi.DatabaseProvider;
import mx.quest.database.spi.Scope;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The guestbook's SQL and scripts on real PostgreSQL and MariaDB, through the real database plugin and providers.
 * Servers come from {@code -Ddb.it.postgresql=host:port}, {@code -Ddb.it.mariadb=host:port} and
 * {@code -Ddb.it.password-file=…}; without them the tests are skipped.
 */
class GuestbookStoreRealServerTest {

    @TempDir
    Path work;

    private final List<AutoCloseable> closing = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closing) {
            c.close();
        }
    }

    private GuestbookStore store(String engine, String siteId) throws Exception {
        String hostPort = System.getProperty("db.it." + engine);
        String file = System.getProperty("db.it.password-file");
        assumeTrue(hostPort != null && file != null, "no " + engine + " server configured");
        String[] hp = hostPort.split(":");
        String password = Files.readString(Path.of(file)).strip();
        DatabaseProps props = new DatabaseProps();
        props.setProvider(engine);
        props.setNamespace("itd" + (System.nanoTime() % 100_000_000L));
        props.setHost(hp[0]);
        props.setPort(Integer.parseInt(hp[1]));
        props.setDatabase("runway");
        props.setUsername("runway");
        props.setTls("disable");
        DatabaseProvider provider = engine.equals("postgresql") ? new PostgresProvider() : new MariaDbProvider();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        DatabaseRuntime runtime = new DatabaseRuntime(() -> DatabaseConfig.from(props, Optional.of(password)),
                () -> List.of(provider), work.resolve("run"), executor);
        FakeSiteServices sites = new FakeSiteServices()
                .site(new SiteView(siteId, siteId, Locale.ENGLISH, List.of(Locale.ENGLISH), null));
        Files.createDirectories(work.resolve("sites").resolve(siteId));
        SiteSchemas schemas = new SiteSchemas(runtime, new TempDirSiteFiles(work.resolve("sites"), "database"), sites);
        DatabaseCore core = new DatabaseCore(runtime, schemas, new Migrator(runtime), sites, sites);
        DatabasesImpl databases = new DatabasesImpl(core, (cls, id) -> new ClasspathScripts(id));
        closing.add(() -> {
            runtime.stop();
            executor.shutdownNow();
            Engine e = DatabaseRuntime.engineFor(engine);
            try (Connection c = provider.dataSource(new mx.quest.database.spi.ConnectionSettings(hp[0],
                    Integer.parseInt(hp[1]), "runway", "runway", password,
                    mx.quest.database.spi.ConnectionSettings.Tls.DISABLE, null, Duration.ofSeconds(5),
                    Duration.ofSeconds(30))).getConnection()) {
                for (String s : e.listSchemas(c, props.getNamespace())) {
                    e.dropSchema(c, s);
                }
            }
        });
        runtime.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!runtime.available() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(runtime.available()).as(runtime.health().detail()).isTrue();
        return new GuestbookStore(databases, new GuestbookConsumer());
    }

    /** The plugin's own scripts, read from the compiled resources as the database plugin reads them from the jar. */
    record ClasspathScripts(String pluginId) implements ScriptSource {
        @Override
        public Optional<byte[]> read(String entry) throws IOException {
            try (InputStream in = GuestbookStore.class.getResourceAsStream("/" + entry)) {
                return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void entriesAreAddedPagedCappedAndDeleted(String engine) throws Exception {
        // A JVM that is not on UTC, so a time read or written in the JVM's zone shows as hours off below.
        TimeZone zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Mexico_City"));
        try {
            entriesAreAddedPagedCappedAndDeletedIn(engine);
        } finally {
            TimeZone.setDefault(zone);
        }
    }

    private void entriesAreAddedPagedCappedAndDeletedIn(String engine) throws Exception {
        GuestbookStore store = store(engine, "acme");
        Scope site = store.db().siteInBackground("acme");
        Instant before = Instant.now();
        long first = store.add(site, "Ana", "Hola ñ €", 10);
        long second = store.add(site, "Bo", "Hi <b>there</b>", 10);
        assertThat(second).isGreaterThan(first);
        GuestbookStore.Page page = store.page(site, 0, 20);
        assertThat(page.entries()).extracting(GuestbookStore.Entry::name).containsExactly("Bo", "Ana");
        assertThat(page.entries().get(1).message()).isEqualTo("Hola ñ €");
        assertThat(page.entries().get(0).at()).as("the entry's time, in UTC, from either side")
                .isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
        for (int i = 0; i < 13; i++) {
            store.add(site, "n" + i, "m" + i, 10);
        }
        assertThat(store.count(site)).isEqualTo(10);
        GuestbookStore.Page newest = store.page(site, 0, 4);
        assertThat(newest.more()).isTrue();
        assertThat(newest.entries()).extracting(GuestbookStore.Entry::name).containsExactly("n12", "n11", "n10", "n9");
        GuestbookStore.Page last = store.page(site, 2, 4);
        assertThat(last.more()).isFalse();
        assertThat(last.entries()).extracting(GuestbookStore.Entry::name).containsExactly("n4", "n3");
        GuestbookStore.Page far = store.page(site, Integer.MAX_VALUE, 50);
        assertThat(far.entries()).as("a huge page number is an empty page, not an overflowed offset").isEmpty();
        assertThat(far.more()).isFalse();
        long id = newest.entries().get(0).id();
        assertThat(store.delete(site, id)).isTrue();
        assertThat(store.delete(site, id)).isFalse();
        assertThat(store.count(site)).isEqualTo(9);
    }
}
