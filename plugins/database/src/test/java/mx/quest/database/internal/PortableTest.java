package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PortableTest {

    @Test
    void typesRestoreWithinTheirFamilyOnly() {
        assertThat(Portable.compatible("INTEGER", "BIGINT")).isTrue();
        assertThat(Portable.compatible("VARCHAR(80)", "TEXT")).isTrue();
        assertThat(Portable.compatible("JSON", "TEXT")).isTrue();
        assertThat(Portable.compatible("TIMESTAMPTZ", "TIMESTAMP")).isTrue();
        assertThat(Portable.compatible("DECIMAL(10,2)", "DECIMAL(12,4)")).isTrue();
        assertThat(Portable.compatible("TEXT", "BIGINT")).isFalse();
        assertThat(Portable.compatible("BOOLEAN", "SMALLINT")).isFalse();
        assertThat(Portable.compatible("UNSUPPORTED:uuid", "UNSUPPORTED:uuid")).isFalse();
    }

    @Test
    void bothEnginesMapTheirNativeTypesToTheSamePortableOnes() {
        assertThat(PostgresEngine.portableType("timestamp with time zone", null, null, null))
                .isEqualTo(MariaDbEngine.portableType("timestamp", "timestamp(6)", null, null, null));
        assertThat(PostgresEngine.portableType("boolean", null, null, null))
                .isEqualTo(MariaDbEngine.portableType("tinyint", "tinyint(1)", null, null, null));
        assertThat(PostgresEngine.portableType("numeric", null, 10, 2))
                .isEqualTo(MariaDbEngine.portableType("decimal", "decimal(10,2)", null, 10L, 2L));
        assertThat(PostgresEngine.portableType("character varying", 80, null, null))
                .isEqualTo(MariaDbEngine.portableType("varchar", "varchar(80)", 80L, null, null));
        assertThat(PostgresEngine.portableType("uuid", null, null, null)).startsWith(Portable.UNSUPPORTED);
        assertThat(MariaDbEngine.portableType("tinyint", "tinyint(4)", null, null, null)).isEqualTo("SMALLINT");
    }
}
