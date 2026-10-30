package mx.quest.database.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import mx.quest.database.spi.ConnectionSettings.Tls;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.ProviderSettings;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class PostgresProviderTest {

    static ConnectionSettings settings(String host, int port, String password, Tls tls, Path ca) {
        return new ConnectionSettings(host, port, "runway", "runway", password, tls, ca, Duration.ofSeconds(5),
                Duration.ofSeconds(40));
    }

    @Test
    void tlsIsExactlyAsConfiguredAndTimeoutsArePinned() {
        PostgresProvider p = new PostgresProvider();
        PGSimpleDataSource off = (PGSimpleDataSource) p.dataSource(settings("db", 0, "pw", Tls.DISABLE, null));
        assertThat(off.getSslMode()).isEqualTo("disable");
        assertThat(off.getPortNumbers()).containsExactly(5432);
        assertThat(off.getConnectTimeout()).isEqualTo(5);
        assertThat(off.getSocketTimeout()).isEqualTo(40);
        assertThat(off.getTcpKeepAlive()).isTrue();
        PGSimpleDataSource req = (PGSimpleDataSource) p.dataSource(settings("db", 1, "pw", Tls.REQUIRE, null));
        assertThat(req.getSslMode()).isEqualTo("require");
        assertThat(req.getSslfactory()).isEqualTo(PostgresProvider.NON_VALIDATING);
        PGSimpleDataSource jvm = (PGSimpleDataSource) p.dataSource(settings("db", 1, "pw", Tls.VERIFY_FULL, null));
        assertThat(jvm.getSslMode()).isEqualTo("verify-full");
        assertThat(jvm.getSslfactory()).isEqualTo(PostgresProvider.JVM_TRUST);
        PGSimpleDataSource ca = (PGSimpleDataSource) p.dataSource(settings("db", 1, "pw", Tls.VERIFY_FULL,
                Path.of("/x/ca.pem")));
        assertThat(ca.getSslfactory()).isEqualTo(PostgresProvider.CA_FILE);
        assertThat(ca.getSslRootCert()).isEqualTo("/x/ca.pem");
        assertThat(ca.getUrl()).doesNotContain("pw").doesNotContain("password");
        PGSimpleDataSource v6 = (PGSimpleDataSource) p.dataSource(settings("::1", 1, "pw", Tls.DISABLE, null));
        assertThat(v6.getServerNames()).containsExactly("[::1]");
    }

    @Test
    void itConnectsToARealServer() throws Exception {
        String hostPort = System.getProperty("db.it.postgresql");
        String file = System.getProperty("db.it.password-file");
        assumeTrue(hostPort != null && file != null, "no PostgreSQL server configured");
        String[] hp = hostPort.split(":");
        try (Connection c = new PostgresProvider().dataSource(settings(hp[0], Integer.parseInt(hp[1]),
                Files.readString(Path.of(file)).strip(), Tls.DISABLE, null)).getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT current_setting('application_name')")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("runway");
        }
        assertThat(new PostgresProvider().driverVersion()).isEqualTo("pgjdbc 42.7.10");
    }

    /** Turns on @ConfigurationProperties binding, as the kernel's context does. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class Binding {
    }

    @Test
    void theConfigurationRegistersOneProviderNamedForThePlugin() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PluginSettings.class, FakePluginSettings::new);
            ctx.register(Binding.class, PostgresProviderConfig.class);
            ctx.refresh();
            assertThat(ctx.getBeanNamesForType(mx.quest.database.spi.DatabaseProvider.class))
                    .containsExactly("databasePostgresqlProvider");
            for (String name : ctx.getBeanDefinitionNames()) {
                if ("postgresProviderConfig".equals(ctx.getBeanDefinition(name).getFactoryBeanName())) {
                    assertThat(name).as("bean names carry the plugin id").startsWith("databasePostgresql");
                }
            }
        }
    }

    @Test
    void theServerSettingsAreThisPluginsOwnRunningAndSaved() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            String prefix = "runway.plugin.database-postgresql.";
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    prefix + "host", "db.running", prefix + "port", "6000", prefix + "database", "runway",
                    prefix + "username", "rw", prefix + "password", "ENC(only-the-stored-form)",
                    prefix + "tls", "require", prefix + "collation", "utf8mb4_uca1400_ai_ci",
                    "runway.plugin.database.host", "not-this-plugins")));
            ctx.registerBean(PluginSettings.class, () -> new FakePluginSettings().appSecret("password", "running-pw")
                    .savedApp("host", "db.saved").savedApp("database", "runway").savedApp("username", "rw2")
                    .savedApp("password", "saved-pw"));
            ctx.register(Binding.class, PostgresProviderConfig.class);
            ctx.refresh();
            PostgresProvider provider = ctx.getBean(PostgresProvider.class);

            ProviderSettings running = provider.runningSettings().orElseThrow();
            assertThat(running.host()).isEqualTo("db.running");
            assertThat(running.port()).isEqualTo("6000");
            assertThat(running.username()).isEqualTo("rw");
            assertThat(running.tls()).isEqualTo("require");
            assertThat(running.password()).as("in clear, from appSecret").isEqualTo("running-pw");
            assertThat(running.collation()).as("a MariaDB setting, never handed over").isNull();

            ProviderSettings saved = provider.savedSettings().orElseThrow();
            assertThat(saved.host()).isEqualTo("db.saved");
            assertThat(saved.username()).isEqualTo("rw2");
            assertThat(saved.password()).isEqualTo("saved-pw");
            assertThat(saved.port()).as("unsaved: blank, the product's default").isNull();
        }
    }

    @Test
    void withoutSettingsTheProviderSaysNothingAsBeforeTwo() {
        assertThat(new PostgresProvider().runningSettings()).isEmpty();
        assertThat(new PostgresProvider().savedSettings()).isEmpty();
    }

    @Test
    void theCompiledClassesLinkNoHostInternals() throws Exception {
        Path classes = Path.of("target", "classes");
        Pattern host = Pattern.compile("mx[/.]quest[/.]runway[/.](?!spi[/.])[\\w/.$]*");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".class")).toList()) {
                Matcher m = host.matcher(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    problems.add(f + " → " + m.group());
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
