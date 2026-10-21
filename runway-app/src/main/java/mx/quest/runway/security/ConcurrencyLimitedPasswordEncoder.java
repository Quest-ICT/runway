package mx.quest.runway.security;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A {@link PasswordEncoder} that caps how many hashes are computed at once. Argon2id deliberately
 * costs tens of MiB and milliseconds per hash; without a bound, a flood of concurrent logins (encode
 * on sign-up/reset, matches on login) could exhaust a small VM's memory. This wrapper bounds the
 * in-flight work to {@code maxConcurrent} and fails a request that cannot get a slot within
 * {@code acquireTimeoutMillis} with {@link PasswordHashingBusyException} — a transient back-pressure
 * signal, not a credential decision. The memory ceiling is therefore {@code maxConcurrent × memoryKiB}.
 *
 * <p>Fairness is on, so requests are served roughly in arrival order under contention. The wrapped
 * encoder does the real work; this class adds only the gate.
 */
public final class ConcurrencyLimitedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final long acquireTimeoutMillis;

    /**
     * @param delegate            the real encoder (e.g. an Argon2id encoder)
     * @param maxConcurrent       the most hashes computed at once (must be &gt; 0)
     * @param acquireTimeoutMillis how long to wait for a slot before failing busy
     */
    public ConcurrencyLimitedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, long acquireTimeoutMillis) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be > 0");
        }
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return withPermit(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withPermit(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        // Cheap metadata check — no hashing, so no permit needed.
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T withPermit(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PasswordHashingBusyException("interrupted while waiting for a password-hashing slot");
        }
        if (!acquired) {
            throw new PasswordHashingBusyException(
                    "no password-hashing slot available within " + acquireTimeoutMillis + " ms");
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
