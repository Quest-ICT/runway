package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import mx.quest.database.spi.ConnectionSettings.Tls;
import org.junit.jupiter.api.Test;

class DatabaseConfigTest {

    static DatabaseProps props() {
        DatabaseProps p = new DatabaseProps();
        p.setProvider("postgresql");
        p.setHost("db.example.com");
        p.setDatabase("runway");
        p.setUsername("runway");
        return p;
    }

    @Test
    void aCompleteSetOfSettingsIsCheckedWithSafeDefaults() {
        DatabaseConfig c = DatabaseConfig.from(props(), Optional.of("s3cret"));
        assertThat(c.tls()).isEqualTo(Tls.VERIFY_FULL);
        assertThat(c.namespace()).isEqualTo("rw");
        assertThat(c.port()).isZero();
        assertThat(c.poolMax()).isEqualTo(10);
        assertThat(c.toString()).doesNotContain("s3cret");
    }

    @Test
    void hostsThatCouldSmuggleDriverSyntaxAreRefused() {
        for (String bad : new String[] {"a,b", "h?ssl=false", "h/db", "u@h", "h:1:2:3", "address=(host=x)", "h h",
                "h;x", "-h", "h-.com", "1.2.3.256", "999.1.1.1", "[::1]x", "fe80::1%eth0", "a..b", "h.",
                "::1::2"}) {
            DatabaseProps p = props();
            p.setHost(bad);
            assertThatThrownBy(() -> DatabaseConfig.from(p, Optional.empty())).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String good : new String[] {"localhost", "db-1.internal", "10.0.0.5", "::1", "[2001:db8::1]",
                "2001:db8:0:0:0:0:0:1", "::ffff:10.0.0.1"}) {
            DatabaseProps p = props();
            p.setHost(good);
            assertThat(DatabaseConfig.from(p, Optional.empty()).host()).as(good).doesNotContain("[");
        }
    }

    @Test
    void theOtherFieldsAreCheckedToo() {
        DatabaseProps p = props();
        p.setDatabase("run way");
        assertThatThrownBy(() -> DatabaseConfig.from(p, Optional.empty())).hasMessageContaining("database name");
        DatabaseProps q = props();
        q.setTls("prefer");
        assertThatThrownBy(() -> DatabaseConfig.from(q, Optional.empty())).hasMessageContaining("tls");
        DatabaseProps r = props();
        r.setProvider("mysql");
        assertThatThrownBy(() -> DatabaseConfig.from(r, Optional.empty())).hasMessageContaining("unknown provider");
        DatabaseProps s = props();
        s.setCaCertificate("not a certificate");
        assertThatThrownBy(() -> DatabaseConfig.from(s, Optional.empty())).hasMessageContaining("PEM");
        DatabaseProps t = props();
        t.setCollation("utf8mb4; DROP");
        assertThatThrownBy(() -> DatabaseConfig.from(t, Optional.empty())).hasMessageContaining("collation");
        DatabaseProps u = props();
        u.setPoolMax(100000);
        u.setStatementTimeoutSeconds(0);
        DatabaseConfig c = DatabaseConfig.from(u, Optional.empty());
        assertThat(c.poolMax()).isEqualTo(DatabaseConfig.POOL_MAX);
        assertThat(c.statementTimeout().toSeconds()).isEqualTo(DatabaseConfig.TIMEOUT_MIN_S);
        DatabaseProps v = props();
        v.setProvider(null);
        assertThatThrownBy(() -> DatabaseConfig.from(v, Optional.empty())).hasMessageContaining("no provider");
    }
}
