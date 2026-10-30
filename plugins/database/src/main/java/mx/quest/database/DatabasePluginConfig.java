package mx.quest.database;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import mx.quest.database.console.DatabaseConsoleController;
import mx.quest.database.console.DatabaseScreens;
import mx.quest.database.internal.ConnectionTester;
import mx.quest.database.internal.DatabaseBackup;
import mx.quest.database.internal.DatabaseCore;
import mx.quest.database.internal.DatabaseLifecycle;
import mx.quest.database.internal.DatabaseProps;
import mx.quest.database.internal.DatabaseRuntime;
import mx.quest.database.internal.DatabaseSiteEvents;
import mx.quest.database.internal.DatabasesImpl;
import mx.quest.database.internal.Migrator;
import mx.quest.database.internal.ProviderChoice;
import mx.quest.database.internal.SiteSchemas;
import mx.quest.database.spi.DatabaseConsumer;
import mx.quest.database.spi.DatabaseProvider;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.spi.service.Sites;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The database plugin's beans, every one registered explicitly and named {@code database…}. Nothing here connects:
 * the runtime connects on the plugin's executor after the platform has started ({@link DatabaseLifecycle}).
 *
 * <p>Consumers inject {@link mx.quest.database.spi.Databases} (the {@code databaseDatabases} bean) by type;
 * providers and consumer declarations are collected by type from the other plugins, lazily, once everything is
 * registered.
 */
@Configuration(proxyBeanMethods = false)
public class DatabasePluginConfig {

    @Bean
    @ConfigurationProperties("runway.plugin.database")
    public DatabaseProps databaseProps() {
        return new DatabaseProps();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService databaseExecutor() {
        AtomicInteger count = new AtomicInteger();
        ThreadFactory threads = r -> {
            Thread t = new Thread(r, "runway-database-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(4, threads);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Bean
    public DatabaseRuntime databaseRuntime(DatabaseProps databaseProps, ObjectProvider<DatabaseProvider> providers,
                                           PluginInfo self, ScheduledExecutorService databaseExecutor) {
        Supplier<List<DatabaseProvider>> found = () -> providers.orderedStream().toList();
        return new DatabaseRuntime(() -> ProviderChoice.running(databaseProps, found.get()), workDir(self),
                databaseExecutor);
    }

    @Bean
    public SiteSchemas databaseSiteSchemas(DatabaseRuntime databaseRuntime, PluginSiteFiles files, Sites sites) {
        return new SiteSchemas(databaseRuntime, files, sites);
    }

    @Bean
    public Migrator databaseMigrator(DatabaseRuntime databaseRuntime) {
        return new Migrator(databaseRuntime);
    }

    @Bean
    public DatabaseCore databaseCore(DatabaseRuntime databaseRuntime, SiteSchemas databaseSiteSchemas,
                                     Migrator databaseMigrator, Sites sites, RequestContext requestContext) {
        return new DatabaseCore(databaseRuntime, databaseSiteSchemas, databaseMigrator, sites, requestContext);
    }

    @Bean
    public DatabasesImpl databaseDatabases(DatabaseCore databaseCore) {
        return new DatabasesImpl(databaseCore);
    }

    @Bean
    public DatabaseBackup databaseBackup(DatabaseCore databaseCore, DatabasesImpl databaseDatabases,
                                         PluginSiteFiles files) {
        DatabaseBackup backup = new DatabaseBackup(databaseCore, databaseDatabases, files);
        databaseCore.migrator().parked(backup);
        return backup;
    }

    @Bean
    public DatabaseSiteEvents databaseSiteEvents(DatabaseCore databaseCore, ScheduledExecutorService databaseExecutor) {
        return new DatabaseSiteEvents(databaseCore, databaseExecutor);
    }

    @Bean
    public DatabaseLifecycle databaseLifecycle(DatabaseCore databaseCore, DatabasesImpl databaseDatabases,
                                               ObjectProvider<DatabaseConsumer> consumers,
                                               ScheduledExecutorService databaseExecutor) {
        return new DatabaseLifecycle(databaseCore, databaseDatabases, () -> consumers.orderedStream().toList(),
                databaseExecutor);
    }

    @Bean
    public ConnectionTester databaseConnectionTester(DatabaseRuntime databaseRuntime, DatabaseProps databaseProps,
                                                     ObjectProvider<DatabaseProvider> providers, PluginInfo self) {
        return new ConnectionTester(databaseRuntime, databaseProps, () -> providers.orderedStream().toList(),
                workDir(self));
    }

    @Bean
    public DatabaseScreens databaseScreens(DatabaseCore databaseCore, DatabasesImpl databaseDatabases,
                                           DatabaseBackup databaseBackup) {
        return new DatabaseScreens(databaseCore, databaseDatabases, databaseBackup);
    }

    @Bean
    public DatabaseConsoleController databaseConsoleController(DatabaseScreens databaseScreens,
                                                               ConnectionTester databaseConnectionTester,
                                                               DatabaseCore databaseCore,
                                                               DatabasesImpl databaseDatabases, Messages messages) {
        return new DatabaseConsoleController(databaseScreens, databaseConnectionTester, databaseCore,
                databaseDatabases, messages);
    }

    /** The plugin's own folder for the CA file it hands the driver. */
    static Path workDir(PluginInfo self) {
        return self.installDir().resolve("run");
    }
}
