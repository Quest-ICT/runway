package mx.quest.runway.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.plugin.PluginAdminService;
import mx.quest.runway.plugin.PluginInstallation;
import mx.quest.runway.site.SiteStore;
import org.springframework.stereotype.Component;

/**
 * Registers a handful of Runway-specific meters on top of the JVM/HTTP metrics Actuator provides for free, so
 * an operator scraping {@code /actuator/prometheus} sees platform-level signal (site count, loaded plugins,
 * dropped log lines) — not just generic process metrics. Implemented as a {@link MeterBinder} so Spring Boot
 * binds it to every registry (notably the Prometheus one) at the right point in startup; each gauge is a cheap
 * read of an existing service, polled at scrape time.
 */
@Component
public class KernelMetrics implements MeterBinder {

    private final SiteStore sites;
    private final PluginAdminService plugins;
    private final SiteLogService logs;
    private final mx.quest.runway.storage.StorageLayout storage;

    public KernelMetrics(SiteStore sites, PluginAdminService plugins, SiteLogService logs,
                         mx.quest.runway.storage.StorageLayout storage) {
        this.sites = sites;
        this.plugins = plugins;
        this.logs = logs;
        this.storage = storage;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("runway.sites", sites, store -> store.findAll().size())
                .description("Number of configured sites")
                .register(registry);

        Gauge.builder("runway.sites.suspended", sites,
                        store -> store.findAll().stream().filter(mx.quest.runway.site.Site::suspended).count())
                .description("Number of operator-suspended sites")
                .register(registry);

        Gauge.builder("runway.sites.maintenance", sites,
                        store -> store.findAll().stream().filter(mx.quest.runway.site.Site::maintenance).count())
                .description("Number of sites in tenant maintenance mode")
                .register(registry);

        Gauge.builder("runway.plugins.loaded", plugins,
                        admin -> admin.list().stream().filter(PluginInstallation::loaded).count())
                .description("Number of plugins currently loaded")
                .register(registry);

        Gauge.builder("runway.logs.dropped", logs, SiteLogService::droppedCount)
                .description("Access/audit log lines dropped under load since startup")
                .register(registry);

        // Free/total space on the filesystem holding the EXTERNAL data directory — the one that matters, since
        // that is where sites, config, logs and backups are written. Deliberately carries NO path label.
        //
        // This replaces Micrometer's stock disk.free/disk.total binder, which we switch off in application.yml.
        // That binder defaults to path "." — the JVM's working directory — and the launcher cds to RUNWAY_HOME,
        // i.e. the VERSIONED release dir. So its label read `path="/opt/runway-<version>/."`: every upgrade minted
        // a brand-new series and abandoned the old one, and any dashboard, alert or recording rule pinning `path=`
        // silently stopped matching — an empty result, never an error. (A monitored deployment had accumulated ten
        // such series in four weeks.) It also measured the wrong thing: an
        // operator cares about space where DATA is written, not where the jar happens to live.
        //
        // A label-free gauge cannot drift, and reading the resolved external dir follows the install's symlink to
        // the version-independent data dir, so the VALUE stays correct across upgrades too.
        Gauge.builder("runway.storage.free.bytes", storage, layout -> usableSpace(layout.external()))
                .description("Free bytes on the filesystem holding the external data directory")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("runway.storage.total.bytes", storage, layout -> totalSpace(layout.external()))
                .description("Total bytes on the filesystem holding the external data directory")
                .baseUnit("bytes")
                .register(registry);
    }

    /** Usable bytes for {@code dir}, or {@code -1} when it cannot be read (never throws into the scrape). */
    private static double usableSpace(java.nio.file.Path dir) {
        try {
            return dir.toFile().getUsableSpace();
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    /** Total bytes for {@code dir}, or {@code -1} when it cannot be read (never throws into the scrape). */
    private static double totalSpace(java.nio.file.Path dir) {
        try {
            return dir.toFile().getTotalSpace();
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }
}
