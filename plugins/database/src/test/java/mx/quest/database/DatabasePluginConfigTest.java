package mx.quest.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import mx.quest.database.internal.DatabaseLifecycle;
import mx.quest.database.internal.DatabaseRuntime;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseProvider;
import mx.quest.database.spi.Databases;
import mx.quest.runway.spi.BackupParticipant;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class DatabasePluginConfigTest {

    @TempDir
    Path dir;

    @Test
    void theConfigurationWiresFromKernelServicesAloneAndConnectsNothingWhileStarting() throws Exception {
        AtomicBoolean touched = new AtomicBoolean();
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PluginSettings.class, FakePluginSettings::new);
            ctx.registerBean(PluginSiteFiles.class, () -> new TempDirSiteFiles(dir.resolve("sites"), "database"));
            ctx.registerBean(PluginInfo.class, () -> new PluginInfo("database", "1.0.0", dir.resolve("plugin")));
            ctx.registerBean(FakeSiteServices.class, FakeSiteServices::new);
            ctx.registerBean("otherPluginProvider", DatabaseProvider.class, () -> new DatabaseProvider() {
                @Override
                public String id() {
                    return "postgresql";
                }

                @Override
                public String driverVersion() {
                    return "none";
                }

                @Override
                public DataSource dataSource(ConnectionSettings settings) {
                    touched.set(true);
                    throw new IllegalStateException("must not be asked while starting");
                }
            });
            ctx.register(DatabasePluginConfig.class);
            ctx.refresh();

            for (String name : ctx.getBeanDefinitionNames()) {
                if (ctx.getBeanDefinition(name).getFactoryMethodName() != null) {
                    assertThat(name).as("bean names carry the plugin id").startsWith("database");
                }
            }
            assertThat(ctx.getBean(Databases.class)).isNotNull();
            assertThat(ctx.getBeansOfType(BackupParticipant.class)).containsKey("databaseBackup");
            assertThat(ctx.getBeansOfType(SiteEventListener.class)).containsKey("databaseSiteEvents");

            ctx.getBean(DatabaseLifecycle.class).started(ctx.getBean(PluginInfo.class));
            DatabaseRuntime runtime = ctx.getBean(DatabaseRuntime.class);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (runtime.status() == DatabaseRuntime.Status.CONNECTING && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            // No settings: nothing is configured, nothing was connected, and Runway carried on. (That a configured
            // plugin connects only on its own thread is aConfiguredPluginConnectsOnItsOwnThreadNeverWhileRunwayStarts.)
            assertThat(runtime.status()).isEqualTo(DatabaseRuntime.Status.NOT_CONFIGURED);
            assertThat(runtime.available()).isFalse();
            assertThat(touched).isFalse();
            ctx.getBean(DatabaseLifecycle.class).stopping();
        }
    }

    /** Turns on @ConfigurationProperties binding, as the kernel's context does. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class Binding {
    }

    @Test
    void aConfiguredPluginConnectsOnItsOwnThreadNeverWhileRunwayStarts() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> askedOn = new AtomicReference<>();
        String startThread = Thread.currentThread().getName();
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "runway.plugin.database.provider", "postgresql", "runway.plugin.database.host", "127.0.0.1",
                    "runway.plugin.database.port", "1", "runway.plugin.database.database", "runway",
                    "runway.plugin.database.username", "runway", "runway.plugin.database.tls", "disable")));
            ctx.registerBean(PluginSettings.class, () -> new FakePluginSettings().appSecret("password", "x"));
            ctx.registerBean(PluginSiteFiles.class, () -> new TempDirSiteFiles(dir.resolve("sites"), "database"));
            ctx.registerBean(PluginInfo.class, () -> new PluginInfo("database", "1.0.0", dir.resolve("plugin")));
            ctx.registerBean(FakeSiteServices.class, FakeSiteServices::new);
            ctx.registerBean("otherPluginProvider", DatabaseProvider.class, () -> new DatabaseProvider() {
                @Override
                public String id() {
                    return "postgresql";
                }

                @Override
                public String driverVersion() {
                    return "none";
                }

                @Override
                public DataSource dataSource(ConnectionSettings settings) {
                    askedOn.compareAndSet(null, Thread.currentThread().getName());
                    try {
                        release.await(10, TimeUnit.SECONDS); // a server that takes its time to answer
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("no server here");
                }
            });
            ctx.register(Binding.class, DatabasePluginConfig.class);
            long t0 = System.nanoTime();
            ctx.refresh();
            ctx.getBean(DatabaseLifecycle.class).started(ctx.getBean(PluginInfo.class));
            long tookMillis = (System.nanoTime() - t0) / 1_000_000;

            // Runway's start went on while the provider was still busy, and never asked it on the starting thread.
            assertThat(tookMillis).as("start did not wait for the database").isLessThan(5_000);
            assertThat(askedOn.get()).as("asked on the starting thread").isNotEqualTo(startThread);
            release.countDown();
            DatabaseRuntime runtime = ctx.getBean(DatabaseRuntime.class);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (askedOn.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(askedOn.get()).as("the settings were real: the background connect reached the provider")
                    .isNotNull().startsWith("runway-database-");
            assertThat(runtime.available()).isFalse();
            ctx.getBean(DatabaseLifecycle.class).stopping();
        }
    }

    @Test
    void theCompiledClassesLinkNoHostInternals() throws IOException {
        Path classes = Path.of("target", "classes");
        Pattern host = Pattern.compile("mx[/.]quest[/.]runway[/.](?!spi[/.])[\\w/.$]*");
        List<String> problems = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                scanned++;
                String rel = classes.relativize(file).toString().replace('\\', '/');
                Matcher m = host.matcher(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    problems.add(rel + " → " + m.group());
                }
            }
        }
        assertThat(scanned).isGreaterThan(30);
        assertThat(problems).isEmpty();
    }
}
