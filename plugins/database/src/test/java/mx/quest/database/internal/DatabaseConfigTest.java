package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mx.quest.database.spi.ConnectionSettings.Tls;
import mx.quest.database.spi.ProviderSettings;
import org.junit.jupiter.api.Test;

class DatabaseConfigTest {

    static ProviderSettings server() {
        return server("db.example.com", null, null);
    }

    static ProviderSettings server(String host, String port, String tls) {
        return new ProviderSettings(host, port, "runway", "runway", "s3cret", tls, null, null);
    }

    static ProviderSettings with(ProviderSettings s, String database, String ca, String collation) {
        return new ProviderSettings(s.host(), s.port(), database, s.username(), s.password(), s.tls(), ca, collation);
    }

    @Test
    void aCompleteSetOfSettingsIsCheckedWithSafeDefaults() {
        DatabaseConfig c = DatabaseConfig.from(new DatabaseProps(), "postgresql", server());
        assertThat(c.tls()).isEqualTo(Tls.VERIFY_FULL);
        assertThat(c.namespace()).isEqualTo("rw");
        assertThat(c.port()).isZero();
        assertThat(c.poolMax()).isEqualTo(10);
        assertThat(c.password()).isEqualTo("s3cret");
        assertThat(c.toString()).doesNotContain("s3cret");
        assertThat(server().toString()).doesNotContain("s3cret");
    }

    @Test
    void hostsThatCouldSmuggleDriverSyntaxAreRefused() {
        for (String bad : new String[] {"a,b", "h?ssl=false", "h/db", "u@h", "h:1:2:3", "address=(host=x)", "h h",
                "h;x", "-h", "h-.com", "1.2.3.256", "999.1.1.1", "[::1]x", "fe80::1%eth0", "a..b", "h.",
                "::1::2"}) {
            assertThatThrownBy(() -> DatabaseConfig.from(new DatabaseProps(), "postgresql", server(bad, null, null)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        for (String good : new String[] {"localhost", "db-1.internal", "10.0.0.5", "::1", "[2001:db8::1]",
                "2001:db8:0:0:0:0:0:1", "::ffff:10.0.0.1"}) {
            assertThat(DatabaseConfig.from(new DatabaseProps(), "postgresql", server(good, null, null)).host())
                    .as(good).doesNotContain("[");
        }
    }

    @Test
    void thePortIsBlankForTheDefaultOrANumberInRange() {
        DatabaseProps p = new DatabaseProps();
        assertThat(DatabaseConfig.from(p, "mariadb", server("h", " ", null)).port()).isZero();
        assertThat(DatabaseConfig.from(p, "mariadb", server("h", "3307", null)).port()).isEqualTo(3307);
        for (String bad : new String[] {"0", "65536", "-1", "33o6", "3306;x"}) {
            assertThatThrownBy(() -> DatabaseConfig.from(p, "mariadb", server("h", bad, null))).as(bad)
                    .hasMessageContaining("port");
        }
    }

    @Test
    void theOtherFieldsAreCheckedToo() {
        DatabaseProps p = new DatabaseProps();
        assertThatThrownBy(() -> DatabaseConfig.from(p, "postgresql", with(server(), "run way", null, null)))
                .hasMessageContaining("database name");
        assertThatThrownBy(() -> DatabaseConfig.from(p, "postgresql", server("h", null, "prefer")))
                .hasMessageContaining("tls");
        assertThatThrownBy(() -> DatabaseConfig.from(p, "mysql", server())).hasMessageContaining("unknown provider");
        assertThatThrownBy(() -> DatabaseConfig.from(p, " ", server())).hasMessageContaining("no provider");
        assertThatThrownBy(() -> DatabaseConfig.from(p, "postgresql", with(server(), "runway", "not a certificate",
                null))).hasMessageContaining("PEM");
        assertThatThrownBy(() -> DatabaseConfig.from(p, "mariadb", with(server(), "runway", null, "utf8mb4; DROP")))
                .hasMessageContaining("collation");
        assertThatThrownBy(() -> DatabaseConfig.from(p, "postgresql", new ProviderSettings("h", null, "runway",
                null, null, null, null, null))).hasMessageContaining("username");
        DatabaseProps u = new DatabaseProps();
        u.setPoolMax(100000);
        u.setStatementTimeoutSeconds(0);
        DatabaseConfig c = DatabaseConfig.from(u, "postgresql", server());
        assertThat(c.poolMax()).isEqualTo(DatabaseConfig.POOL_MAX);
        assertThat(c.statementTimeout().toSeconds()).isEqualTo(DatabaseConfig.TIMEOUT_MIN_S);
    }
}
