package mx.quest.database.mariadb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.ConnectionSettings.Tls;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MariaDbProviderTest {

    static ConnectionSettings settings(String host, int port, String database, String password, Tls tls, Path ca) {
        return new ConnectionSettings(host, port, database, "runway", password, tls, ca, Duration.ofSeconds(5),
                Duration.ofSeconds(40));
    }

    @Test
    void theUrlCarriesOnlyThePinnedPropertiesAndNeverTheAccount() {
        String url = MariaDbProvider.url(settings("db", 0, "runway", "s3cret", Tls.DISABLE, null));
        assertThat(url).startsWith("jdbc:mariadb://db:3306/runway?")
                .contains("allowLocalInfile=false", "allowMultiQueries=false", "permitRedirect=false",
                        "allowPublicKeyRetrieval=false", "connectionTimeZone=UTC",
                        "sslMode=disable",
                        "connectTimeout=5000", "socketTimeout=40000")
                .doesNotContain("preserveInstants").doesNotContain("s3cret").doesNotContain("user=").doesNotContain("password");
        assertThat(MariaDbProvider.url(settings("db", 1, "runway", "", Tls.REQUIRE, null))).contains("sslMode=trust");
        assertThat(MariaDbProvider.url(settings("db", 1, "runway", "", Tls.VERIFY_FULL, null)))
                .contains("sslMode=verify-full").doesNotContain("serverSslCert");
        assertThat(MariaDbProvider.url(settings("db", 1, "runway", "", Tls.VERIFY_FULL, Path.of("/a b/ca.pem"))))
                .contains("serverSslCert=/a b/ca.pem");
        assertThat(MariaDbProvider.url(settings("::1", 1, "run$way", "", Tls.DISABLE, null)))
                .startsWith("jdbc:mariadb://[::1]:1/run$way?");
        assertThatThrownBy(() -> MariaDbProvider.url(settings("db", 1, "runway", "", Tls.VERIFY_FULL,
                Path.of("/x&sslMode=disable/ca.pem")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectorJReadsTheValuesAsWritten() throws Exception {
        // The driver decodes nothing: what the URL says is the file it opens and the database it names.
        var conf = org.mariadb.jdbc.Configuration.parse(MariaDbProvider.url(settings("db", 1, "run$way", "",
                Tls.VERIFY_FULL, Path.of("/opt/run way/ca-abc.pem"))));
        assertThat(conf.database()).isEqualTo("run$way");
        assertThat(conf.serverSslCert()).isEqualTo("/opt/run way/ca-abc.pem");
        assertThat(conf.connectionTimeZone()).isEqualTo("UTC");
        assertThat(conf.preserveInstants()).isFalse();
    }

    @Test
    void itConnectsToARealServerAndInstantsGoBothWaysAsUtcWhateverTheJvmZone() throws Exception {
        String hostPort = System.getProperty("db.it.mariadb");
        String file = System.getProperty("db.it.password-file");
        assumeTrue(hostPort != null && file != null, "no MariaDB server configured");
        String[] hp = hostPort.split(":");
        TimeZone jvm = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Mexico_City")); // a JVM that is not on UTC
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try (Connection c = new MariaDbProvider().dataSource(settings(hp[0], Integer.parseInt(hp[1]), "runway",
                Files.readString(Path.of(file)).strip(), Tls.DISABLE, null)).getConnection()) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT VERSION()")) {
                rs.next();
                assertThat(rs.getString(1)).contains("MariaDB");
            }
            try (Statement s = c.createStatement()) {
                s.execute("SET time_zone = '+00:00'"); // what the database plugin pins on every connection
                s.execute("CREATE TEMPORARY TABLE rw_times (at DATETIME(6) NOT NULL)");
            }
            // The way the database plugin's backups and registry write and read (Portable, MariaDbEngine.instant).
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // DATETIME(6)
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO rw_times (at) VALUES (?)")) {
                ps.setObject(1, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT at, UTC_TIMESTAMP(6), "
                    + "TIMESTAMPDIFF(SECOND, ?, UTC_TIMESTAMP(6)) FROM rw_times")) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()), utc);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC))
                            .as("a UTC LocalDateTime read back as written").isEqualTo(now);
                    assertThat(rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC))
                            .as("the server's UTC clock read as a LocalDateTime")
                            .isBetween(now.minusSeconds(5), Instant.now().plusSeconds(5));
                    assertThat(rs.getTimestamp(1, utc).toInstant()).as("read as a Timestamp with a UTC Calendar")
                            .isEqualTo(now);
                    assertThat(Math.abs(rs.getLong(3))).as("a Timestamp bound with a UTC Calendar")
                            .isLessThanOrEqualTo(5);
                }
            }
        } finally {
            TimeZone.setDefault(jvm);
        }
        assertThat(new MariaDbProvider().driverVersion()).startsWith("MariaDB Connector/J");
    }

    @Test
    void theConfigurationRegistersOneProviderNamedForThePlugin() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                MariaDbProviderConfig.class)) {
            assertThat(ctx.getBeanNamesForType(mx.quest.database.spi.DatabaseProvider.class))
                    .containsExactly("databaseMariadbProvider");
        }
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
