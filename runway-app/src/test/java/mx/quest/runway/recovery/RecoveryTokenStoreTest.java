package mx.quest.runway.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recovery-token store's security properties: tokens are hashed at rest (the file never holds a usable
 * token), single-use (consumed on redeem), time-limited (expired grants vanish), one-per-account (a new link
 * invalidates the prior one), and each grant carries its realm/site scope.
 */
class RecoveryTokenStoreTest {

    private static final long T0 = 1_700_000_000_000L; // a fixed "now" the tests advance from
    private static final Duration TTL = Duration.ofHours(1);

    private static RecoveryTokenStore store(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        return new RecoveryTokenStore(StorageLayout.from(props), new YamlStore());
    }

    @Test
    void issueThenPeekFindsAValidGrantAndCarriesTheScope(@TempDir Path root) {
        RecoveryTokenStore store = store(root);
        String token = store.issue(Realm.TENANT, "acme", "u1", "alice", TTL, T0);

        Optional<RecoveryToken> peeked = store.peek(token, T0);
        assertTrue(peeked.isPresent(), "a freshly issued token is valid");
        assertEquals(Realm.TENANT, peeked.get().realm());
        assertEquals("acme", peeked.get().siteId());
        assertEquals("u1", peeked.get().principalId());
        assertEquals("alice", peeked.get().username());
        assertTrue(store.peek("not-a-real-token", T0).isEmpty(), "an unknown token is not valid");
    }

    @Test
    void theTokenIsHashedAtRestNotStoredInTheClear(@TempDir Path root) throws IOException {
        RecoveryTokenStore store = store(root);
        String token = store.issue(Realm.APPLICATION, null, "u1", "admin", TTL, T0);

        Path file = root.resolve("external").resolve("recovery-tokens.yml");
        String onDisk = Files.readString(file);
        assertFalse(onDisk.contains(token), "the plaintext token is never written to disk");
        assertTrue(onDisk.contains("tokenHash"), "only the hash is stored");
    }

    @Test
    void consumeIsSingleUse(@TempDir Path root) {
        RecoveryTokenStore store = store(root);
        String token = store.issue(Realm.APPLICATION, null, "u1", "admin", TTL, T0);

        assertTrue(store.consume(token, T0).isPresent(), "the first redeem succeeds");
        assertTrue(store.consume(token, T0).isEmpty(), "the token cannot be redeemed twice");
        assertTrue(store.peek(token, T0).isEmpty(), "and is gone after use");
    }

    @Test
    void anExpiredGrantIsNotValid(@TempDir Path root) {
        RecoveryTokenStore store = store(root);
        String token = store.issue(Realm.APPLICATION, null, "u1", "admin", TTL, T0);

        long afterExpiry = T0 + TTL.toMillis() + 1;
        assertTrue(store.peek(token, afterExpiry).isEmpty(), "past its TTL the grant is invalid");
        assertTrue(store.consume(token, afterExpiry).isEmpty(), "an expired grant cannot be redeemed");
    }

    @Test
    void issuingANewLinkInvalidatesThePriorOneForTheSameAccount(@TempDir Path root) {
        RecoveryTokenStore store = store(root);
        String first = store.issue(Realm.APPLICATION, null, "u1", "admin", TTL, T0);
        String second = store.issue(Realm.APPLICATION, null, "u1", "admin", TTL, T0 + 1000);

        assertTrue(store.peek(first, T0 + 1000).isEmpty(), "the old link is invalidated");
        assertTrue(store.peek(second, T0 + 1000).isPresent(), "the new link works");
    }

    @Test
    void grantsForDifferentAccountsCoexist(@TempDir Path root) {
        RecoveryTokenStore store = store(root);
        String a = store.issue(Realm.APPLICATION, null, "u1", "admin", TTL, T0);
        String b = store.issue(Realm.TENANT, "acme", "u2", "bob", TTL, T0);

        assertTrue(store.peek(a, T0).isPresent(), "one account's grant is unaffected by another's");
        assertTrue(store.peek(b, T0).isPresent());
    }
}
