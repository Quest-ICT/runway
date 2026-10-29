package mx.quest.database.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import mx.quest.database.spi.DatabaseConsumer;
import mx.quest.database.spi.Scope;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.PluginLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and stops the database plugin. {@link #started} only hands work to the executor — the first connection,
 * then (each time the database becomes available) the reconcile sweep and the migration warm-up — so Runway's
 * start never waits on the database. {@link #stopping} closes the pools and stops the executor.
 */
public final class DatabaseLifecycle implements PluginLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DatabaseLifecycle.class);

    static final Duration SWEEP_EVERY = Duration.ofMinutes(15);

    private final DatabaseCore core;
    private final DatabasesImpl databases;
    private final Supplier<List<DatabaseConsumer>> consumers;
    private final ScheduledExecutorService executor;

    public DatabaseLifecycle(DatabaseCore core, DatabasesImpl databases, Supplier<List<DatabaseConsumer>> consumers,
                             ScheduledExecutorService executor) {
        this.core = core;
        this.databases = databases;
        this.consumers = consumers;
        this.executor = executor;
    }

    @Override
    public void started(PluginInfo self) {
        DatabaseRuntime runtime = core.runtime();
        runtime.onAvailable(() -> {
            databases.registerAll(consumers.get(), refused -> log.warn("database: a consumer was refused: {}",
                    refused));
            core.schemas().reconcile();
            warmUp();
        });
        runtime.start();
        executor.scheduleWithFixedDelay(core.schemas()::reconcile, SWEEP_EVERY.toMinutes(), SWEEP_EVERY.toMinutes(),
                TimeUnit.MINUTES);
    }

    /**
     * Brings every consumer's tables up to date ahead of the first call, one schema at a time. Only a warm-up: the
     * check each first call makes is the authority, so a failure here is logged and nothing more.
     */
    void warmUp() {
        DatabaseRuntime runtime = core.runtime();
        List<Consumer> all = databases.consumers();
        if (all.isEmpty() || !runtime.available()) {
            return;
        }
        String app = Names.appSchema(runtime.config().namespace());
        List<Registry.SiteRow> sites;
        try (Connection c = runtime.setupConnection()) {
            sites = runtime.registry().sites(c);
        } catch (SQLException e) {
            log.warn("database: the warm-up could not list the sites: {}", DatabaseRuntime.describe(e));
            return;
        }
        for (Consumer consumer : all) {
            if (consumer.scopes().contains(Scope.Kind.APP)) {
                core.migrator().warm(consumer, Scope.Kind.APP, null, app);
            }
            if (consumer.scopes().contains(Scope.Kind.SITE)) {
                for (Registry.SiteRow site : sites) {
                    if (SiteSchemas.LIVE.equals(site.state()) && core.sites().find(site.siteId()).isPresent()) {
                        core.migrator().warm(consumer, Scope.Kind.SITE, site.siteId(), site.schema());
                    }
                }
            }
        }
    }

    @Override
    public void stopping() {
        core.runtime().stop();
        executor.shutdownNow();
    }
}
