package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MariaDbEngineTest {

    @Test
    void mariaDbBefore1011IsRefusedByVersionNotByAReadOnlyVariable() throws Exception {
        assertThat(MariaDbEngine.checkServer("MariaDB", "10.11.0-MariaDB", "mariadb.org binary distribution"))
                .isEqualTo("MariaDB 10.11.0");
        assertThat(MariaDbEngine.checkServer("MariaDB", "11.4.5-MariaDB-ubu2404", "")).isEqualTo("MariaDB 11.4.5");
        for (String old : new String[] {"10.10.7-MariaDB", "10.6.21-MariaDB-log", "10.9.8-MariaDB", "5.5.68-MariaDB"}) {
            assertThatThrownBy(() -> MariaDbEngine.checkServer("MariaDB", old, ""))
                    .isInstanceOf(UnsupportedServerException.class)
                    .hasMessageContaining("too old: 10.11 or later is needed");
        }
    }

    @Test
    void mySqlIsNeverTakenForMariaDb() {
        assertThatThrownBy(() -> MariaDbEngine.checkServer("MySQL", "8.4.0", "MySQL Community Server - GPL"))
                .isInstanceOf(UnsupportedServerException.class).hasMessageContaining("MySQL is not supported");
        assertThatThrownBy(() -> MariaDbEngine.checkServer("MariaDB", "11.4.5-MariaDB", "MySQL fork"))
                .isInstanceOf(UnsupportedServerException.class).hasMessageContaining("MySQL is not supported");
    }

    @Test
    void everySessionGetsExplicitTimestampDefaultsAndUtc() {
        String init = new MariaDbEngine().connectionInitSql(java.time.Duration.ofSeconds(30), null);
        assertThat(init).contains("explicit_defaults_for_timestamp = 1").contains("time_zone = '+00:00'");
    }
}
