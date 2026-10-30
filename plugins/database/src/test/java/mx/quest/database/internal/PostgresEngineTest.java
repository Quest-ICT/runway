package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** {@link PostgresEngine#classify}: what an operator is told when a connection fails. */
class PostgresEngineTest {

    private final PostgresEngine engine = new PostgresEngine();

    @Test
    void tlsAskedOfAServerWithoutItIsATlsProblemNotARefusedPassword() {
        assertThat(engine.classify(new SQLException("The server does not support SSL.", "08004")))
                .isEqualTo(Engine.ConnectFailure.TLS);
        assertThat(engine.classify(new SQLException("El servidor no soporta SSL.", "08004")))
                .as("pgjdbc translates the message; SSL stays").isEqualTo(Engine.ConnectFailure.TLS);
    }

    @Test
    void theOtherRefusalsKeepTheirMeaning() {
        assertThat(engine.classify(new SQLException("The server requested password-based authentication, but no "
                + "password was provided.", "08004"))).isEqualTo(Engine.ConnectFailure.AUTHENTICATION);
        assertThat(engine.classify(new SQLException("password authentication failed", "28P01")))
                .isEqualTo(Engine.ConnectFailure.AUTHENTICATION);
        assertThat(engine.classify(new SQLException("database \"x\" does not exist", "3D000")))
                .isEqualTo(Engine.ConnectFailure.DATABASE_MISSING);
        assertThat(engine.classify(new SQLException("Connection refused", "08001")))
                .isEqualTo(Engine.ConnectFailure.UNREACHABLE);
    }
}
