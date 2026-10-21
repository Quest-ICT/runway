package mx.quest.runway.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The single source of truth for Runway's password hashing — one shared factory so the application
 * encoder bean and any test can never drift on the Argon2id parameters. Hashing is always Argon2id;
 * legacy SHA-based hashing is forbidden.
 *
 * <p>The argument order of {@link Argon2PasswordEncoder} is a known trap —
 * {@code (saltLength, hashLength, parallelism, memoryInKiB, iterations)} — so transposing
 * parallelism and iterations silently produces weaker, off-standard hashes that still "work". Keeping
 * the construction in one place (covered by a parameter-asserting test) prevents that.
 */
public final class PasswordEncoders {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int ITERATIONS = 2;

    private PasswordEncoders() {
    }

    /**
     * An Argon2id encoder at the given {@link PasswordStrength}. The realms share one encoder (their
     * separation is in the stores and chains, not the hashing).
     *
     * @param strength the memory-cost profile (never {@code null})
     * @return a configured {@link Argon2PasswordEncoder} (requires BouncyCastle on the classpath)
     */
    public static Argon2PasswordEncoder argon2id(PasswordStrength strength) {
        return new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, strength.memoryKiB(), ITERATIONS);
    }

    /**
     * An Argon2id encoder wrapped in a {@link ConcurrencyLimitedPasswordEncoder}, so a flood of
     * concurrent logins degrades to a graceful "busy" rather than exhausting memory on a small VM.
     *
     * @param strength           the memory-cost profile
     * @param maxConcurrentHashes the most hashes computed at once
     * @param acquireTimeoutMillis how long a request waits for a slot before failing busy
     */
    public static PasswordEncoder argon2idLimited(PasswordStrength strength, int maxConcurrentHashes,
                                                  long acquireTimeoutMillis) {
        return new ConcurrencyLimitedPasswordEncoder(argon2id(strength), maxConcurrentHashes, acquireTimeoutMillis);
    }
}
