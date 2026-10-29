package mx.quest.database.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import mx.quest.database.spi.DatabaseException;
import mx.quest.database.spi.DatabaseUnavailableException;
import mx.quest.database.spi.Scope;
import mx.quest.database.spi.ScopeNotReadyException;
import mx.quest.database.spi.SqlWork;

/**
 * One consumer's scope: a site's schema or the app schema. Each outer call takes the site's in-flight slot, borrows
 * one connection, binds it (checking the mapping in the same round trip), runs the work in one transaction and hands
 * the connection back reset. A call on the same scope from inside the work joins the outer call; a call on another
 * scope from inside it throws, so a consumer cannot hold two connections and starve the pool.
 */
final class ScopeImpl implements Scope {

    /** The outer call this thread is inside, if any. */
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();

    private record Active(String key, Connection connection, boolean readOnly) {
    }

    private final DatabaseCore core;
    private final Consumer consumer;
    private final Kind kind;
    private final String siteId;
    private final String key;

    ScopeImpl(DatabaseCore core, Consumer consumer, Kind kind, String siteId) {
        this.core = core;
        this.consumer = consumer;
        this.kind = kind;
        this.siteId = siteId;
        this.key = consumer.pluginId() + "|" + kind + "|" + (siteId == null ? "" : siteId);
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public String siteId() {
        return siteId;
    }

    @Override
    public <T> T transaction(SqlWork<T> work) {
        return call(work, false);
    }

    @Override
    public <T> T read(SqlWork<T> work) {
        return call(work, true);
    }

    private <T> T call(SqlWork<T> work, boolean readOnly) {
        Objects.requireNonNull(work, "work");
        Active active = ACTIVE.get();
        if (active != null) {
            if (!active.key().equals(key)) {
                throw new IllegalStateException("a database call on one scope from inside a call on another scope; "
                        + "finish the first call before starting the second");
            }
            if (active.readOnly() && !readOnly) {
                throw new IllegalStateException("transaction() inside read(): a read-only call cannot write");
            }
            return runJoined(work, active.connection());
        }
        if (!consumer.scopes().contains(kind)) {
            throw new IllegalStateException("plugin '" + consumer.pluginId() + "' did not declare the " + kind
                    + " scope in its DatabaseConsumer");
        }
        DatabaseRuntime runtime = core.runtime();
        if (!runtime.available()) {
            throw new DatabaseUnavailableException("the database is not available");
        }
        Semaphore slots = kind == Kind.SITE ? core.siteSlots(siteId) : core.appSlots();
        try {
            if (!slots.tryAcquire(DatabaseRuntime.BORROW_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new DatabaseUnavailableException("too many database calls in flight for site '" + siteId + "'");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseUnavailableException("interrupted while waiting for a database connection");
        }
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String schema = kind == Kind.SITE ? core.schemas().resolve(siteId)
                        : Names.appSchema(runtime.config().namespace());
                core.migrator().ensure(consumer, kind, siteId, schema);
                Connection c = runtime.borrow();
                boolean bound;
                try {
                    bound = bind(c, schema);
                } catch (SQLException | RuntimeException e) {
                    runtime.evict(c);
                    throw new DatabaseUnavailableException("the connection could not be bound to the site's data", e);
                }
                if (!bound) {
                    close(c, false);
                    core.schemas().evict(siteId); // the mapping changed on another node: resolve again
                    continue;
                }
                return runOuter(work, c, readOnly);
            }
            throw new ScopeNotReadyException(ScopeNotReadyException.Reason.PREPARING,
                    "the site's data moved while the call started; try again");
        } finally {
            slots.release();
        }
    }

    private boolean bind(Connection c, String schema) throws SQLException {
        DatabaseRuntime runtime = core.runtime();
        Engine engine = runtime.engine();
        if (kind == Kind.SITE) {
            return engine.bindSite(c, runtime.registry().appSchema(), siteId, schema,
                    runtime.config().statementTimeout());
        }
        engine.bind(c, schema, runtime.config().statementTimeout());
        return true;
    }

    private <T> T runOuter(SqlWork<T> work, Connection c, boolean readOnly) {
        Engine engine = core.runtime().engine();
        boolean broken = false;
        try {
            c.setAutoCommit(false);
            if (readOnly) {
                c.setReadOnly(true);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                engine.beginRead(c);
            }
        } catch (SQLException e) {
            close(c, true);
            throw new DatabaseUnavailableException("the connection could not start a transaction", e);
        }
        ACTIVE.set(new Active(key, c, readOnly));
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException e) {
            broken = !rollback(c);
            throw new DatabaseException("the database call failed: " + DatabaseRuntime.describe(e), e);
        } catch (RuntimeException | Error e) {
            broken = !rollback(c);
            throw e;
        } finally {
            ACTIVE.remove();
            close(c, broken);
        }
    }

    /** A call joined to the outer one: the same connection, no commit of its own; a throw rolls back the outer. */
    private static <T> T runJoined(SqlWork<T> work, Connection c) {
        try {
            return work.run(c);
        } catch (SQLException e) {
            throw new DatabaseException("the database call failed: " + DatabaseRuntime.describe(e), e);
        }
    }

    private static boolean rollback(Connection c) {
        try {
            c.rollback();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Resets and returns a connection, or throws it away when it may carry state the next borrower must not see. */
    private void close(Connection c, boolean broken) {
        DatabaseRuntime runtime = core.runtime();
        if (!broken) {
            try {
                c.setAutoCommit(true);
                runtime.engine().reset(c);
            } catch (SQLException | RuntimeException e) {
                broken = true;
            }
        }
        if (broken) {
            runtime.evict(c);
            return;
        }
        try {
            c.close();
        } catch (SQLException ignored) {
            // the pool discards it
        }
    }
}
