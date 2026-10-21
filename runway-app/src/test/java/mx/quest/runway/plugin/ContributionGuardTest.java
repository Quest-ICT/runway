package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The containment policy: what is caught, what is substituted, what is logged, counted and remembered. */
class ContributionGuardTest {

    /** A Clock whose instant the test moves. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-17T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock();
    private ContributionGuard guard;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    @BeforeEach
    void setUp() {
        guard = new ContributionGuard(PluginOwnership.of(null), provider(meters), clock);
        logger = (Logger) LoggerFactory.getLogger(ContributionGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        RequestContextHolder.resetRequestAttributes();
    }

    private double count(String axis, String key) {
        var counter = meters.find(ContributionGuard.METRIC).tag("plugin", "kernel").tag("axis", axis)
                .tag("key", key).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aRuntimeExceptionIsSubstitutedCountedAndLoggedWithItsStack() {
        String result = guard.call(this, ContributionGuard.Axis.HEAD, "seo", () -> {
            throw new IllegalStateException("boom");
        }, () -> "fallback");

        assertEquals("fallback", result);
        assertEquals(1.0, count("head", "seo"));
        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertNotNull(event.getThrowableProxy(), "the first failure carries its stack trace");
        assertTrue(event.getFormattedMessage().contains("'kernel'") && event.getFormattedMessage().contains("seo"),
                event.getFormattedMessage());
        ContributionGuard.PluginHealth health = guard.health(ContributionGuard.KERNEL);
        assertEquals(1, health.failures());
        assertEquals("IllegalStateException: boom", health.lastError());
        assertEquals(clock.instant(), health.lastErrorAt());
    }

    @Test
    void aLinkageErrorIsContainedTooButOtherErrorsPropagate() {
        assertEquals("ok", guard.call(this, ContributionGuard.Axis.WIDGET, "w", () -> {
            throw new NoSuchMethodError("mx.quest.runway.spi.model.PageMeta.gone()");
        }, () -> "ok"));
        assertThrows(StackOverflowError.class, () -> guard.call(this, ContributionGuard.Axis.WIDGET, "w", () -> {
            throw new StackOverflowError();
        }, () -> "never"));
        assertThrows(StackOverflowError.class, () -> guard.run(this, ContributionGuard.Axis.LIFECYCLE, "l", () -> {
            throw new StackOverflowError();
        }));
        assertEquals(1.0, count("widget", "w"), "only the contained failure is counted");
    }

    @Test
    void callNonNullTreatsANullResultAsAFailureButCallDoesNot() {
        assertEquals("sub", guard.callNonNull(this, ContributionGuard.Axis.HEAD_INPUTS, "k", () -> null, () -> "sub"));
        assertEquals(1.0, count("head-inputs", "k"));
        assertNull(guard.call(this, ContributionGuard.Axis.QUALITY, "q", () -> null, () -> "sub"));
        assertEquals(0.0, count("quality", "q"));
    }

    @Test
    void runReportsWhetherTheActionCompleted() {
        assertTrue(guard.run(this, ContributionGuard.Axis.LIFECYCLE, "ok", () -> { }));
        assertFalse(guard.run(this, ContributionGuard.Axis.LIFECYCLE, "bad", () -> {
            throw new RuntimeException("x");
        }));
    }

    @Test
    void repeatsAreLoggedAtMostOncePerMinuteWithTheSuppressedCount() {
        Runnable fail = () -> guard.run(this, ContributionGuard.Axis.ENDPOINT, "route", () -> {
            throw new RuntimeException("again");
        });
        fail.run();                // logged with stack
        fail.run();                // suppressed
        fail.run();                // suppressed
        clock.advanceSeconds(30);
        fail.run();                // still inside the minute: suppressed
        assertEquals(1, appender.list.size());
        clock.advanceSeconds(31);
        fail.run();                // a minute after the last log: logged with the count
        assertEquals(2, appender.list.size());
        ILoggingEvent repeat = appender.list.get(1);
        assertTrue(repeat.getFormattedMessage().contains("+3 suppressed"), repeat.getFormattedMessage());
        assertNull(repeat.getThrowableProxy(), "repeats are one line, without the stack");
        assertEquals(5.0, count("endpoint", "route"), "every failure is counted, logged or not");
        assertEquals(5, guard.health(ContributionGuard.KERNEL).failures());
    }

    @Test
    void distinctKeysAreRateLimitedIndependently() {
        guard.run(this, ContributionGuard.Axis.HEAD, "a", () -> { throw new RuntimeException(); });
        guard.run(this, ContributionGuard.Axis.HEAD, "b", () -> { throw new RuntimeException(); });
        assertEquals(2, appender.list.size());
    }

    @Test
    void aFailureOnARequestThreadMarksTheRequestDegraded() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertFalse(ContributionGuard.isDegraded(request));
        guard.run(this, ContributionGuard.Axis.WIDGET, "w", () -> { throw new RuntimeException(); });
        assertTrue(ContributionGuard.isDegraded(request));
    }

    @Test
    void aFailureOffARequestThreadDoesNotThrow() {
        RequestContextHolder.resetRequestAttributes();
        assertEquals("s", guard.call(this, ContributionGuard.Axis.LISTENER, "l", () -> {
            throw new RuntimeException();
        }, () -> "s"));
    }

    @Test
    void withoutAMeterRegistryNothingBreaks() {
        ContributionGuard bare = ContributionGuard.standalone();
        assertEquals("s", bare.call(this, ContributionGuard.Axis.HEAD, "x", () -> {
            throw new RuntimeException();
        }, () -> "s"));
        assertEquals(1, bare.health("kernel").failures());
    }

    @Test
    void kernelCollisionsAndBlankKernelKeysFailFast() {
        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> guard.index("HeadContributor", List.of("a1", "a2"), s -> "same"));
        assertTrue(duplicate.getMessage().contains("Duplicate HeadContributor key 'same'"), duplicate.getMessage());
        assertThrows(IllegalStateException.class, () -> guard.index("HeadContributor", List.of("x"), s -> " "));
        Map<String, String> fine = guard.index("HeadContributor", List.of("a", "b"), s -> s);
        assertEquals(List.of("a", "b"), List.copyOf(fine.keySet()), "iteration order is kept");
    }

    @Test
    void anUnknownPluginIdIsClean() {
        assertEquals(ContributionGuard.PluginHealth.CLEAN, guard.health("nobody"));
        assertFalse(guard.health("nobody").hasProblems());
    }
}
