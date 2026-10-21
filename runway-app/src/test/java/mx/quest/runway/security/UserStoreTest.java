package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The user-store primitive: round-trips, upsert-by-id, case-insensitive lookup, eviction, forward-compat. */
class UserStoreTest {

    private static User user(String id, String username) {
        return new User(id, username, "$argon2id$" + id, "Name", "e@x", true, false, Realm.APPLICATION,
                Set.of("SYSTEMADMIN"), List.of());
    }

    private static Path file(Path tmp) {
        return tmp.resolve("app-users.yml");
    }

    @Test
    void savesAndFindsAcrossARoundTrip(@TempDir Path tmp) {
        UserStore store = new UserStore(new YamlStore());
        Path file = file(tmp);

        store.save(file, user("u-1", "alice"));
        store.save(file, user("u-2", "bob"));

        assertEquals(2, store.list(file).size());
        assertEquals("u-1", store.findByUsername(file, "ALICE").orElseThrow().principalId(), "login is case-insensitive");
        assertEquals("bob", store.findByPrincipalId(file, "u-2").orElseThrow().username());
        assertTrue(store.findByUsername(file, "nobody").isEmpty());
    }

    @Test
    void saveUpsertsByPrincipalIdRatherThanDuplicating(@TempDir Path tmp) {
        UserStore store = new UserStore(new YamlStore());
        Path file = file(tmp);

        store.save(file, user("u-1", "alice"));
        store.save(file, user("u-1", "alice-renamed")); // same id, new username

        assertEquals(1, store.list(file).size());
        assertEquals("alice-renamed", store.findByPrincipalId(file, "u-1").orElseThrow().username());
    }

    @Test
    void saveEvictsTheCacheSoReadsSeeTheLatest(@TempDir Path tmp) {
        UserStore store = new UserStore(new YamlStore());
        Path file = file(tmp);

        store.save(file, user("u-1", "alice"));
        assertEquals(1, store.list(file).size()); // populates the cache
        store.save(file, user("u-2", "bob"));
        assertEquals(2, store.list(file).size(), "the cache was evicted on save");
    }

    @Test
    void deleteRemovesByIdAndReportsWhetherAnythingWentAway(@TempDir Path tmp) {
        UserStore store = new UserStore(new YamlStore());
        Path file = file(tmp);
        store.save(file, user("u-1", "alice"));

        assertTrue(store.delete(file, "u-1"));
        assertFalse(store.delete(file, "u-1"), "second delete finds nothing");
        assertTrue(store.list(file).isEmpty());
    }

    @Test
    void anAbsentFileIsAnEmptyStore(@TempDir Path tmp) {
        UserStore store = new UserStore(new YamlStore());
        assertTrue(store.list(file(tmp)).isEmpty());
        assertTrue(store.findByUsername(file(tmp), "alice").isEmpty());
    }

    @Test
    void toleratesUnknownYamlKeys(@TempDir Path tmp) throws IOException {
        Path file = file(tmp);
        Files.writeString(file,
                "users:\n- principalId: u-1\n  username: alice\n  realm: APPLICATION\n  enabled: true\n  futureKey: 42\n",
                StandardCharsets.UTF_8);

        // Forward-compatible parsing: an unknown key from a newer version must not break the read.
        assertEquals("alice", new UserStore(new YamlStore()).findByUsername(file, "alice").orElseThrow().username());
    }
}
