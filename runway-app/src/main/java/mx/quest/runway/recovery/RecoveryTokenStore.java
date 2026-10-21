package mx.quest.runway.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import org.springframework.stereotype.Component;

/**
 * The password-recovery token store — {@code external/recovery-tokens.yml}, an application-level file outside
 * every tenant folder (recovery grants are ephemeral platform state, never part of a tenant backup). Holds a
 * hashed, expiring, single-use grant per outstanding reset link.
 *
 * <p><b>Security properties.</b>
 * <ul>
 *   <li><b>Hashed at rest.</b> The link carries a 256-bit {@link SecureRandom} token; only its SHA-256 hash is
 *       stored, so the file never contains a usable token.</li>
 *   <li><b>Single-use.</b> {@link #consume(String)} removes the grant, so a link works exactly once.</li>
 *   <li><b>Expiring.</b> Grants past their TTL are ignored and pruned on every read/write.</li>
 *   <li><b>One live grant per account.</b> {@link #issue} drops any prior grant for the same account, so
 *       requesting a new link invalidates the old one and the file cannot grow per account.</li>
 * </ul>
 *
 * <p>Writes are coarse-{@code synchronized} (load-modify-write); a reset is rare, so this is ample.
 */
@Component
public class RecoveryTokenStore {

    private static final String FILE = "recovery-tokens.yml";
    /** A hard cap on stored grants (defence against a flood of distinct emails); oldest are dropped past it. */
    private static final int MAX_ENTRIES = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageLayout layout;
    private final YamlStore store;

    public RecoveryTokenStore(StorageLayout layout, YamlStore store) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The YAML wrapper (a bare list does not round-trip cleanly through the typed store). */
    public record Bundle(List<RecoveryToken> tokens) {
        public Bundle {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }
    }

    /**
     * Mints a new grant for an account and returns the <b>plaintext</b> token to embed in the reset link (only
     * its hash is stored). Any existing grant for the same account is dropped first (one live link per account).
     *
     * @param realm       the account's realm
     * @param siteId      the account's site for a {@link Realm#TENANT} account; {@code null} for application
     * @param principalId the account's stable id
     * @param username    the account's login name (for the reset page + audit)
     * @param ttl         how long the link stays valid
     * @param nowEpochMs  the current wall-clock instant (injected so callers/tests control time)
     * @return the plaintext token for the emailed link
     */
    public synchronized String issue(Realm realm, String siteId, String principalId, String username,
                                     Duration ttl, long nowEpochMs) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        RecoveryToken grant = new RecoveryToken(sha256Hex(token), realm, siteId, principalId, username,
                nowEpochMs + ttl.toMillis());

        List<RecoveryToken> live = new ArrayList<>(liveTokens(nowEpochMs));
        // Drop any prior grant for the same account (realm + site + principal): one live link per account.
        live.removeIf(t -> t.realm() == realm && Objects.equals(t.siteId(), siteId)
                && t.principalId().equals(principalId));
        live.add(grant);
        if (live.size() > MAX_ENTRIES) {
            // Keep the newest MAX_ENTRIES by expiry (a proxy for recency), dropping the oldest.
            live.sort((a, b) -> Long.compare(b.expiresEpochMs(), a.expiresEpochMs()));
            live = new ArrayList<>(live.subList(0, MAX_ENTRIES));
        }
        write(live);
        return token;
    }

    /**
     * Looks up a still-valid grant for {@code token} <b>without</b> consuming it — used by the reset GET to
     * decide whether to show the new-password form. Returns empty for an unknown, expired or malformed token.
     */
    public synchronized Optional<RecoveryToken> peek(String token, long nowEpochMs) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(token);
        return liveTokens(nowEpochMs).stream().filter(t -> t.tokenHash().equals(hash)).findFirst();
    }

    /**
     * Atomically validates and <b>removes</b> a grant (single use) — used by the reset POST. Returns the grant
     * if it was valid, or empty for an unknown/expired token (in which case nothing is removed).
     */
    public synchronized Optional<RecoveryToken> consume(String token, long nowEpochMs) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(token);
        List<RecoveryToken> live = new ArrayList<>(liveTokens(nowEpochMs));
        Optional<RecoveryToken> match = live.stream().filter(t -> t.tokenHash().equals(hash)).findFirst();
        if (match.isPresent()) {
            live.removeIf(t -> t.tokenHash().equals(hash));
            write(live);
        }
        return match;
    }

    /** The stored grants that are still valid at {@code nowEpochMs} (expired ones filtered out). */
    private List<RecoveryToken> liveTokens(long nowEpochMs) {
        return load().tokens().stream().filter(t -> t.isValidAt(nowEpochMs)).toList();
    }

    private Bundle load() {
        try {
            return store.read(file(), Bundle.class).orElseGet(() -> new Bundle(List.of()));
        } catch (IOException e) {
            throw new UncheckedIOException("reading recovery-tokens.yml", e);
        }
    }

    private void write(List<RecoveryToken> tokens) {
        try {
            store.write(file(), new Bundle(tokens));
        } catch (IOException e) {
            throw new UncheckedIOException("writing recovery-tokens.yml", e);
        }
    }

    private Path file() {
        return layout.external().resolve(FILE);
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
