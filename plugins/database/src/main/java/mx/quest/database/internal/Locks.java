package mx.quest.database.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The per-site lock (allocation, restore and removal take it) and the install-wide named locks. Each is an in-JVM
 * lock first, then a database lock on a dedicated unpooled connection, so a waiter holds no pooled connection and
 * the lock covers every node. A waiter gives up after a bounded time.
 */
final class Locks {

    /** Work run while holding a lock. */
    @FunctionalInterface
    interface Locked<T> {
        T run() throws SQLException;
    }

    /** The lock was not obtained in time. */
    static final class LockTimeoutException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LockTimeoutException(String what) {
            super("timed out waiting for " + what);
        }
    }

    private static final int STRIPES = 64;

    private final DatabaseRuntime runtime;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    Locks(DatabaseRuntime runtime) {
        this.runtime = runtime;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock(true);
        }
    }

    /** Runs {@code work} holding the site's lock. */
    <T> T site(String siteId, Duration wait, Locked<T> work) throws SQLException {
        return hold("site:" + siteId, wait, work);
    }

    /** Runs {@code work} holding an install-wide lock ({@code registry}, {@code plugins}). */
    <T> T global(String name, Duration wait, Locked<T> work) throws SQLException {
        return hold("global:" + name, wait, work);
    }

    private <T> T hold(String name, Duration wait, Locked<T> work) throws SQLException {
        long deadline = System.nanoTime() + wait.toNanos();
        ReentrantLock local = stripes[Math.floorMod(name.hashCode(), STRIPES)];
        try {
            if (!local.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new LockTimeoutException(name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockTimeoutException(name);
        }
        try {
            String key = runtime.config().namespace() + ":" + name;
            Connection c = runtime.openLockConnection(remaining(deadline));
            if (c == null) {
                throw new LockTimeoutException(name);
            }
            try {
                if (!runtime.engine().lock(c, key, remaining(deadline))) {
                    throw new LockTimeoutException(name);
                }
                try {
                    return work.run();
                } finally {
                    try {
                        runtime.engine().unlock(c, key);
                    } catch (SQLException ignored) {
                        // closing the connection below releases it
                    }
                }
            } finally {
                runtime.closeLockConnection(c);
            }
        } finally {
            local.unlock();
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(TimeUnit.MILLISECONDS.toNanos(1), deadline - System.nanoTime()));
    }
}
