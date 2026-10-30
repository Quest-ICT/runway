package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseProvider;
import mx.quest.database.spi.ProviderSettings;
import org.junit.jupiter.api.Test;

/** {@link ProviderChoice}: which provider is used, whose settings, and the reason for every refusal. */
class ProviderChoiceTest {

    private static final ProviderSettings RUNNING = new ProviderSettings("db.running", null, "runway", "runway",
            "running-secret", "disable", null, null);
    private static final ProviderSettings SAVED = new ProviderSettings("db.saved", "5433", "runway", "runway",
            "saved-secret", "require", null, null);
    private static final ProviderSettings EMPTY = ProviderSettings.read(key -> Optional.empty());

    /** A 2.0.0 provider whose running and saved settings are given; {@code null} for one built before 2.0.0. */
    static DatabaseProvider provider(String id, ProviderSettings running, ProviderSettings saved) {
        return new DatabaseProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String driverVersion() {
                return "test";
            }

            @Override
            public DataSource dataSource(ConnectionSettings settings) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ProviderSettings> runningSettings() {
                return Optional.ofNullable(running);
            }

            @Override
            public Optional<ProviderSettings> savedSettings() {
                return Optional.ofNullable(saved);
            }
        };
    }

    private static DatabaseProps choosing(String provider) {
        DatabaseProps p = new DatabaseProps();
        p.setProvider(provider);
        return p;
    }

    @Test
    void autoUsesTheOneInstalledProviderWithItsRunningSettings() {
        DatabaseProvider pg = provider("postgresql", RUNNING, SAVED);
        ProviderChoice.Chosen chosen = ProviderChoice.running(new DatabaseProps(), List.of(pg));
        assertThat(chosen.provider()).isSameAs(pg);
        assertThat(chosen.config().provider()).isEqualTo("postgresql");
        assertThat(chosen.config().host()).isEqualTo("db.running");
        assertThat(chosen.config().password()).isEqualTo("running-secret");
        assertThat(ProviderChoice.running(choosing(" "), List.of(pg)).provider()).as("blank is auto").isSameAs(pg);
        assertThat(ProviderChoice.running(choosing("AUTO"), List.of(provider("mariadb", RUNNING, null)))
                .config().provider()).isEqualTo("mariadb");
    }

    @Test
    void withBothInstalledAutoAsksForAChoiceAndANamedProductIsUsed() {
        DatabaseProvider pg = provider("postgresql", RUNNING, null);
        DatabaseProvider maria = provider("mariadb", RUNNING, null);
        assertThatThrownBy(() -> ProviderChoice.running(new DatabaseProps(), List.of(pg, maria)))
                .isNotInstanceOf(ProviderChoice.NoProviderException.class)
                .hasMessageContaining("both provider plugins are installed");
        assertThat(ProviderChoice.running(choosing("mariadb"), List.of(pg, maria)).provider()).isSameAs(maria);
        assertThat(ProviderChoice.running(choosing("PostgreSQL"), List.of(pg, maria)).provider()).isSameAs(pg);
    }

    @Test
    void aMissingOrDuplicatedProviderIsANoProviderProblem() {
        assertThatThrownBy(() -> ProviderChoice.running(new DatabaseProps(), List.of()))
                .isInstanceOf(ProviderChoice.NoProviderException.class).hasMessageContaining("no provider plugin");
        assertThatThrownBy(() -> ProviderChoice.running(choosing("mariadb"),
                List.of(provider("postgresql", RUNNING, null))))
                .isInstanceOf(ProviderChoice.NoProviderException.class).hasMessageContaining("database-mariadb");
        assertThatThrownBy(() -> ProviderChoice.running(new DatabaseProps(),
                List.of(provider("postgresql", RUNNING, null), provider("postgresql", RUNNING, null))))
                .isInstanceOf(ProviderChoice.NoProviderException.class).hasMessageContaining("remove one");
        assertThatThrownBy(() -> ProviderChoice.running(new DatabaseProps(),
                List.of(provider("oracle", RUNNING, null))))
                .as("a product without SQL here counts as none").isInstanceOf(ProviderChoice.NoProviderException.class);
        assertThatThrownBy(() -> ProviderChoice.running(choosing("mysql"), List.of(provider("postgresql", RUNNING,
                null)))).isNotInstanceOf(ProviderChoice.NoProviderException.class)
                .hasMessageContaining("unknown provider");
    }

    @Test
    void aProviderBuiltBeforeTwoIsReportedAsTooOld() {
        assertThatThrownBy(() -> ProviderChoice.running(new DatabaseProps(), List.of(provider("postgresql", null,
                null)))).isInstanceOf(ProviderChoice.NoProviderException.class)
                .hasMessageContaining("older than 2.0.0").hasMessageContaining("database-postgresql");
    }

    @Test
    void anUnsetServerSaysWhereToSetItAndOneXKeysAreReportedAsMoved() {
        DatabaseProvider pg = provider("postgresql", EMPTY, null);
        assertThatThrownBy(() -> ProviderChoice.running(new DatabaseProps(), List.of(pg)))
                .hasMessageContaining("no server is set").hasMessageContaining("database-postgresql");
        DatabaseProps old = new DatabaseProps();
        old.setHost("db.example");
        old.setUsername("runway");
        old.setPassword("ENC(abc)");
        assertThatThrownBy(() -> ProviderChoice.running(old, List.of(pg)))
                .hasMessageContaining("moved to the database-postgresql plugin")
                .hasMessageContaining("host, username, password")
                .hasMessageContaining("plugin-settings/database-postgresql.yml")
                .hasMessageNotContaining("abc");
        assertThat(ProviderChoice.running(old, List.of(provider("postgresql", RUNNING, null))).config().host())
                .as("1.x keys are never used, even when still present").isEqualTo("db.running");
    }

    @Test
    void anUnusableSettingNamesTheProviderPlugin() {
        ProviderSettings bad = new ProviderSettings("db.example", "99999", "runway", "runway", null, null, null,
                null);
        assertThatThrownBy(() -> ProviderChoice.running(new DatabaseProps(), List.of(provider("mariadb", bad,
                null)))).hasMessageStartingWith("database-mariadb settings: ").hasMessageContaining("port");
    }

    @Test
    void theSavedSettingsAreThoseOfTheGivenProviderWhateverIsRunning() {
        DatabaseProvider pg = provider("postgresql", RUNNING, SAVED);
        ProviderChoice.Chosen chosen = ProviderChoice.saved(choosing("mariadb"), pg);
        assertThat(chosen.config().host()).isEqualTo("db.saved");
        assertThat(chosen.config().port()).isEqualTo(5433);
        assertThat(chosen.config().password()).isEqualTo("saved-secret");
        assertThatThrownBy(() -> ProviderChoice.saved(new DatabaseProps(), provider("postgresql", RUNNING, EMPTY)))
                .hasMessageContaining("no server is saved");
        assertThatThrownBy(() -> ProviderChoice.saved(new DatabaseProps(), provider("postgresql", RUNNING, null)))
                .hasMessageContaining("older than 2.0.0");
    }
}
