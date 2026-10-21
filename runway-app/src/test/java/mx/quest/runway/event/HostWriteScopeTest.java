package mx.quest.runway.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link HostWriteScope}: post-commit delivery, nesting, reentrancy, failure and the storm bound. */
class HostWriteScopeTest {

    private final List<String> log = new ArrayList<>();

    @AfterEach
    void noLeftovers() {
        assertEquals(0, HostWriteScope.depth(), "every test leaves the thread clean");
        assertFalse(HostWriteScope.draining());
    }

    @Test
    void outsideAnyScopeADispatchRunsAtOnce() {
        HostWriteScope.dispatch(() -> log.add("now"));
        assertEquals(List.of("now"), log);
    }

    @Test
    void insideAScopeADispatchWaitsForTheScopeToClose() {
        try (HostWriteScope scope = HostWriteScope.enter()) {
            HostWriteScope.dispatch(() -> log.add("deferred"));
            assertEquals(List.of(), log, "nothing runs while the scope (and the lock it wraps) is open");
            assertEquals(1, HostWriteScope.depth());
        }
        assertEquals(List.of("deferred"), log);
    }

    @Test
    void onlyTheOutermostCloseDrainsAndOrderIsKept() {
        try (HostWriteScope outer = HostWriteScope.enter()) {
            HostWriteScope.dispatch(() -> log.add("a"));
            try (HostWriteScope inner = HostWriteScope.enter()) {
                HostWriteScope.dispatch(() -> log.add("b"));
            }
            assertEquals(List.of(), log, "an inner close does not drain: the outer lock may still be held");
            HostWriteScope.dispatch(() -> log.add("c"));
        }
        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void aCallbackRunsWithNoScopeOpen() {
        AtomicInteger depthSeen = new AtomicInteger(-1);
        try (HostWriteScope scope = HostWriteScope.enter()) {
            HostWriteScope.dispatch(() -> depthSeen.set(HostWriteScope.depth()));
        }
        assertEquals(0, depthSeen.get());
    }

    @Test
    void aWriteFromACallbackIsDeliveredAfterTheCurrentCallbackNotInsideIt() {
        try (HostWriteScope scope = HostWriteScope.enter()) {
            HostWriteScope.dispatch(() -> {
                log.add("first:start");
                // the callback writes: its own scope, its own dispatch
                try (HostWriteScope nested = HostWriteScope.enter()) {
                    HostWriteScope.dispatch(() -> log.add("nested"));
                }
                log.add("first:end");
            });
            HostWriteScope.dispatch(() -> log.add("second"));
        }
        assertEquals(List.of("first:start", "first:end", "second", "nested"), log,
                "the nested change is delivered after the callback that caused it, behind earlier dispatches");
    }

    @Test
    void anInlineDispatchFromACallbackIsAlsoQueuedBehindIt() {
        HostWriteScope.dispatch(() -> {
            log.add("outer:start");
            HostWriteScope.dispatch(() -> log.add("inner"));
            log.add("outer:end");
        });
        assertEquals(List.of("outer:start", "outer:end", "inner"), log);
    }

    @Test
    void aFailingDispatchDoesNotStopTheNext() {
        try (HostWriteScope scope = HostWriteScope.enter()) {
            HostWriteScope.dispatch(() -> {
                throw new IllegalStateException("boom");
            });
            HostWriteScope.dispatch(() -> log.add("next"));
        }
        assertEquals(List.of("next"), log);
    }

    @Test
    void aWriteThatFailsAfterPublishingStillDeliversWhatItPublished() {
        assertThrows(IllegalStateException.class, () -> {
            try (HostWriteScope scope = HostWriteScope.enter()) {
                HostWriteScope.dispatch(() -> log.add("published-before-failure"));
                throw new IllegalStateException("the write failed afterwards");
            }
        });
        assertEquals(List.of("published-before-failure"), log);
    }

    @Test
    void closeIsIdempotent() {
        HostWriteScope outer = HostWriteScope.enter();
        HostWriteScope inner = HostWriteScope.enter();
        inner.close();
        inner.close(); // must not close the outer one too
        assertEquals(1, HostWriteScope.depth());
        outer.close();
    }

    @Test
    void aListenerAnsweringEveryChangeWithAnotherIsCutOff() {
        AtomicInteger runs = new AtomicInteger();
        Runnable[] storm = new Runnable[1];
        storm[0] = () -> {
            runs.incrementAndGet();
            HostWriteScope.dispatch(storm[0]);
        };
        HostWriteScope.dispatch(storm[0]);
        assertEquals(HostWriteScope.MAX_DISPATCHES_PER_DRAIN, runs.get(), "bounded, not infinite");
        HostWriteScope.dispatch(() -> log.add("later"));
        assertEquals(List.of("later"), log, "the thread is usable again afterwards");
    }

    @Test
    void scopesAreThreadLocal() throws Exception {
        try (HostWriteScope scope = HostWriteScope.enter()) {
            AtomicInteger otherDepth = new AtomicInteger(-1);
            List<String> otherLog = new ArrayList<>();
            Thread other = new Thread(() -> {
                otherDepth.set(HostWriteScope.depth());
                HostWriteScope.dispatch(() -> otherLog.add("ran"));
            });
            other.start();
            other.join();
            assertEquals(0, otherDepth.get());
            assertEquals(List.of("ran"), otherLog, "another thread is not held back by this thread's scope");
            assertTrue(log.isEmpty());
        }
    }
}
