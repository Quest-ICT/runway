package mx.quest.runway.event;

import java.util.ArrayDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-commit dispatch of plugin callbacks: plugin {@code SiteEventListener} callbacks must
 * never run while the host holds a write lock, yet must still have run when the write call returns.
 *
 * <h2>How</h2>
 * Every host section that publishes one of the four content-shaping events while holding a write lock enters a scope
 * <b>outside</b> the lock: {@code try (var s = HostWriteScope.enter()) { synchronized (lock) { … publish … } }}. The
 * SPI bridge hands each plugin dispatch to {@link #dispatch}. Inside a scope (depth &gt; 0) the dispatch is queued on
 * the thread; when the <b>outermost</b> scope closes — after the lock is released, since the scope wraps it — the
 * queue drains in order. Outside any scope the dispatch drains at once. Kernel cache eviction is unaffected: it still
 * runs synchronously inside the lock, as the event's own Spring listeners.
 *
 * <h2>Reentrancy</h2>
 * A callback may write (a page save from a listener): that write opens its own scope, its dispatch is queued, and the
 * queue that is already draining picks it up <b>after</b> the current callback returns — never nested inside it, and
 * never reordered ahead of dispatches queued earlier. A drain that keeps growing (a listener answering every change
 * with another write) is cut off after {@link #MAX_DISPATCHES_PER_DRAIN} dispatches with an ERROR.
 *
 * <h2>Failure</h2>
 * The drain runs from {@link #close()}, so it also runs when the write fails after publishing: the evictions that
 * happened are still delivered. A dispatch that throws is logged and the next one runs (the bridge already isolates
 * each listener; this is the backstop).
 *
 * <p>State is per thread and released when the thread's queue is empty and no scope is open. Not thread-safe by
 * design: a scope belongs to the thread that opened it.
 */
public final class HostWriteScope implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HostWriteScope.class);

    /** Bound on the dispatches one outermost drain delivers — a guard against listener-driven write storms. */
    public static final int MAX_DISPATCHES_PER_DRAIN = 1_000;

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private static final class State {
        int depth;
        boolean draining;
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    }

    private boolean closed;

    private HostWriteScope() {
    }

    /**
     * Opens a scope on the current thread. Scopes nest; only the outermost close drains. Use it in a
     * try-with-resources <b>around</b> the lock it protects.
     *
     * @return the scope to close
     */
    public static HostWriteScope enter() {
        state().depth++;
        return new HostWriteScope();
    }

    /** How many scopes are open on the current thread ({@code 0} outside any). A test hook as much as an API. */
    public static int depth() {
        State state = STATE.get();
        return state == null ? 0 : state.depth;
    }

    /** Whether the current thread is delivering queued dispatches right now. */
    public static boolean draining() {
        State state = STATE.get();
        return state != null && state.draining;
    }

    /**
     * Delivers {@code dispatch} on the current thread: queued while a scope is open or a drain is in progress (it runs
     * later in that same drain), run at once otherwise.
     *
     * @param dispatch the plugin dispatch to deliver (never {@code null})
     */
    public static void dispatch(Runnable dispatch) {
        State state = state();
        state.queue.addLast(dispatch);
        if (state.depth == 0 && !state.draining) {
            drain(state);
        }
    }

    /** Closes the scope; the outermost close drains the thread's queue. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        State state = STATE.get();
        if (state == null) {
            return; // cannot happen with balanced use; nothing to drain
        }
        state.depth = Math.max(0, state.depth - 1);
        if (state.depth == 0 && !state.draining) {
            drain(state);
        }
    }

    private static State state() {
        State state = STATE.get();
        if (state == null) {
            state = new State();
            STATE.set(state);
        }
        return state;
    }

    private static void drain(State state) {
        state.draining = true;
        int delivered = 0;
        try {
            Runnable next;
            while ((next = state.queue.pollFirst()) != null) {
                if (++delivered > MAX_DISPATCHES_PER_DRAIN) {
                    log.error("plugin event dispatch: {} dispatches in one drain — a listener is answering changes "
                            + "with more changes; dropping the {} still queued", MAX_DISPATCHES_PER_DRAIN,
                            state.queue.size() + 1);
                    state.queue.clear();
                    break;
                }
                try {
                    next.run();
                } catch (RuntimeException failure) {
                    log.warn("plugin event dispatch failed; continuing with the next one", failure);
                }
            }
        } finally {
            state.draining = false;
            if (state.depth == 0 && state.queue.isEmpty()) {
                STATE.remove();
            }
        }
    }
}
