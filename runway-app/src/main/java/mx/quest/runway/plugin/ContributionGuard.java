package mx.quest.runway.plugin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The plugin-contribution containment policy in one place: every call the host makes into contributed
 * code on a request or dispatch path — and every run of a plugin's {@code @Scheduled} method — goes through
 * {@link #call}/{@link #run}, so one plugin's bug costs one fragment, one finding, one 500 or one skipped run — never
 * the page, the publish, the log, or the platform.
 *
 * <h2>What is caught</h2>
 * {@link RuntimeException} and {@link LinkageError} (a {@code NoSuchMethodError} or {@code NoClassDefFoundError}
 * from a plugin built against a different kernel is the classic case). Any other {@link Error} — an
 * {@code OutOfMemoryError}, a {@code StackOverflowError} — propagates: the JVM is no longer trustworthy, and
 * swallowing it would hide the one failure an operator must see. {@code BackupParticipant.export} is deliberately
 * never guarded: its failure fails the backup (a backup is complete or it is not a backup).
 *
 * <h2>What a failure leaves behind</h2>
 * <ul>
 *   <li>The caller's substitute value (an empty fragment, an empty list, a warning finding — the caller decides,
 *       per axis).</li>
 *   <li>On a request thread, the request is marked {@linkplain #isDegraded degraded}: the page that carries a
 *       substituted fragment is served {@code Cache-Control: no-store} with no validators, so a transient fault is
 *       never pinned by a browser or CDN.</li>
 *   <li>A log line: the first failure per {@code (plugin, axis, key)} is a WARN <b>with</b> the stack trace; after
 *       that, at most one WARN per minute per key, carrying the count of failures suppressed since the last
 *       one.</li>
 *   <li>The counter {@code runway.plugin.contribution.failures{plugin,axis,key}} — {@code plugin} is
 *       {@code kernel} for the kernel's own contributors.</li>
 *   <li>Per plugin, a failure count and the last error with its time, shown on the plugins screen.</li>
 * </ul>
 *
 * <p>There is no circuit breaker on purpose: a throwing contributor costs a stack unwind, not a timeout, and a
 * breaker would add state and a silent-absence failure mode.
 *
 * <p>The same class records <b>collision refusals</b>: when two contributions claim one key, the
 * plugin side (or the later plugin in load order) is refused and its plugin is marked degraded. See
 * {@link #index}.
 *
 * <p>Thread-safe.
 */
@Component
public class ContributionGuard {

    private static final Logger log = LoggerFactory.getLogger(ContributionGuard.class);

    /** The request attribute marking a response that carries a substituted contribution. */
    public static final String DEGRADED_ATTRIBUTE = "runway.plugin.degraded";

    /** The failure counter's name (a metric name is configuration-and-data contract). */
    public static final String METRIC = "runway.plugin.contribution.failures";

    /** The {@code plugin} tag value for the kernel's own contributors. */
    public static final String KERNEL = "kernel";

    private static final long REPEAT_LOG_INTERVAL_MILLIS = 60_000L;
    /** Bound on tracked (plugin, axis, key) log states; cleared wholesale past it (costs one extra log line). */
    private static final int MAX_TRACKED_KEYS = 4096;

    /** The call sites the policy covers — the {@code axis} tag. */
    public enum Axis {
        HEAD("head"), HEAD_INPUTS("head-inputs"), WIDGET("widget"), QUALITY("quality"), ENDPOINT("endpoint"),
        CONTENT_SOURCE("content-source"), LIFECYCLE("lifecycle"), BACKUP_IMPORT("backup-import"),
        LISTENER("listener"),
        /** A plugin's {@code @Scheduled} method ({@link ContainedSchedulingPostProcessor}; since SPI 1.0.0). */
        SCHEDULED("scheduled");

        private final String tag;

        Axis(String tag) {
            this.tag = tag;
        }

        /** The metric/log tag value. */
        public String tag() {
            return tag;
        }
    }

    /** One plugin's failure record, for the plugins screen. */
    public record PluginHealth(long failures, String lastError, Instant lastErrorAt, List<String> degraded) {
        public PluginHealth {
            degraded = List.copyOf(degraded);
        }

        /** A plugin with nothing recorded. */
        public static final PluginHealth CLEAN = new PluginHealth(0, null, null, List.of());

        /** Whether anything is worth showing. */
        public boolean hasProblems() {
            return failures > 0 || !degraded.isEmpty();
        }
    }

    private final PluginOwnership ownership;
    private final ObjectProvider<MeterRegistry> meters;
    private final Clock clock;
    private final Map<String, LogState> logStates = new ConcurrentHashMap<>();
    private final Map<String, Record> perPlugin = new ConcurrentHashMap<>();

    @Autowired
    public ContributionGuard(PluginOwnership ownership, ObjectProvider<MeterRegistry> meters) {
        this(ownership, meters, Clock.systemUTC());
    }

    ContributionGuard(PluginOwnership ownership, ObjectProvider<MeterRegistry> meters, Clock clock) {
        this.ownership = ownership;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * A guard with no plugin attribution and no metrics: every contribution counts as kernel code. What the
     * registries use when built outside a Spring context (unit tests), so their historical fail-fast behaviour on
     * a kernel collision is unchanged.
     */
    public static ContributionGuard standalone() {
        return new ContributionGuard(PluginOwnership.of(null), null, Clock.systemUTC());
    }

    // ---- containment -------------------------------------------------------------------------------------

    /**
     * Calls {@code call} on behalf of {@code contribution}; on a {@link RuntimeException} or {@link LinkageError}
     * records the failure and returns {@code substitute.get()}. A {@code null} result is returned as is — callers
     * for which {@code null} is a contract breach use {@link #callNonNull}.
     */
    public <T> T call(Object contribution, Axis axis, String key, Supplier<T> call, Supplier<T> substitute) {
        try {
            return call.get();
        } catch (RuntimeException | LinkageError failure) {
            record(contribution, axis, key, failure);
            return substitute.get();
        }
    }

    /** As {@link #call}, but a {@code null} result also counts as a failure (the axis contract forbids it). */
    public <T> T callNonNull(Object contribution, Axis axis, String key, Supplier<T> call, Supplier<T> substitute) {
        return call(contribution, axis, key, () -> {
            T result = call.get();
            if (result == null) {
                throw new IllegalStateException("returned null");
            }
            return result;
        }, substitute);
    }

    /** Runs {@code action}; a failure is recorded and swallowed. Returns whether it completed normally. */
    public boolean run(Object contribution, Axis axis, String key, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            record(contribution, axis, key, failure);
            return false;
        }
    }

    /**
     * Records a failure the caller caught itself (where the call shape does not fit {@link #call}, e.g. a
     * checked exception): counter, rate-limited log, per-plugin record, request degrade mark.
     */
    public void record(Object contribution, Axis axis, String key, Throwable failure) {
        String plugin = pluginOf(contribution);
        String safeKey = key == null ? "?" : key;
        markRequestDegraded();
        count(plugin, axis, safeKey);
        perPlugin.computeIfAbsent(plugin, id -> new Record()).failed(failure, clock.instant());
        logFailure(plugin, axis, safeKey, failure);
    }

    private void logFailure(String plugin, Axis axis, String key, Throwable failure) {
        String stateKey = plugin + "|" + axis.tag() + "|" + key;
        if (logStates.size() > MAX_TRACKED_KEYS) {
            logStates.clear();
        }
        LogState state = logStates.computeIfAbsent(stateKey, k -> new LogState());
        long suppressed = state.claim(clock.millis());
        if (suppressed == LogState.SKIP) {
            return;
        }
        if (suppressed == LogState.FIRST) {
            log.warn("plugin '{}' {} '{}' failed — substituted and continuing", plugin, axis.tag(), key, failure);
        } else {
            log.warn("plugin '{}' {} '{}' failed again: {} (+{} suppressed in the last minute)", plugin, axis.tag(),
                    key, failure.toString(), suppressed);
        }
    }

    private void count(String plugin, Axis axis, String key) {
        MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
        if (registry != null) {
            Counter.builder(METRIC)
                    .description("Calls into contributed (plugin or kernel) code that threw and were substituted")
                    .tag("plugin", plugin).tag("axis", axis.tag()).tag("key", key)
                    .register(registry).increment();
        }
    }

    /** The plugin id {@code contribution} belongs to, or {@link #KERNEL}. */
    public String pluginOf(Object contribution) {
        if (contribution == null) {
            return KERNEL;
        }
        Class<?> type = contribution instanceof Class<?> c ? c : contribution.getClass();
        return ownership.pluginIdOf(type).orElse(KERNEL);
    }

    // ---- request degrade marker --------------------------------------------------------------------------

    private static void markRequestDegraded() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(DEGRADED_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** Whether a guarded call substituted a contribution during {@code request}. */
    public static boolean isDegraded(jakarta.servlet.ServletRequest request) {
        return request != null && Boolean.TRUE.equals(request.getAttribute(DEGRADED_ATTRIBUTE));
    }

    // ---- collision refusal ---------------------------------------------------------------------------

    /**
     * Indexes contributions by key with the collision rules, preserving iteration order:
     * <ul>
     *   <li>a blank key, or two contributions sharing a key, among <b>kernel</b> contributions fails startup
     *       ({@link IllegalStateException}) — a kernel defect must never be papered over;</li>
     *   <li>a blank key on a <b>plugin</b> contribution refuses that contribution;</li>
     *   <li>a collision involving a plugin refuses the plugin's contribution when the other side is the kernel's,
     *       and the <b>later</b> plugin's (in load order) when both are plugins.</li>
     * </ul>
     * A refusal logs an ERROR naming both sides and marks the refused plugin degraded; the plugin's other
     * contributions still load.
     *
     * @param axisName the human axis name for messages ({@code HeadContributor}, …)
     */
    public <C> Map<String, C> index(String axisName, List<C> contributions, Function<C, String> keyOf) {
        Map<String, C> byKey = new LinkedHashMap<>();
        for (C contribution : contributions) {
            String key = keyOf.apply(contribution);
            String plugin = pluginOf(contribution);
            if (key == null || key.isBlank()) {
                if (KERNEL.equals(plugin)) {
                    throw new IllegalStateException(axisName + " " + contribution.getClass().getName()
                            + " has a null/blank key()");
                }
                refuse(plugin, axisName + " " + contribution.getClass().getName() + " has a blank key()");
                continue;
            }
            C existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, contribution);
                continue;
            }
            String existingPlugin = pluginOf(existing);
            String clash = axisName + " key '" + key + "': " + describe(existing, existingPlugin) + " and "
                    + describe(contribution, plugin);
            if (KERNEL.equals(plugin) && KERNEL.equals(existingPlugin)) {
                throw new IllegalStateException("Duplicate " + clash);
            }
            if (loserIsIncoming(existingPlugin, plugin)) {
                refuse(plugin, "collides on " + clash + " — refused " + plugin + "'s");
            } else {
                byKey.put(key, contribution); // the incoming side wins: the existing one is the later plugin's
                refuse(existingPlugin, "collides on " + clash + " — refused " + existingPlugin + "'s");
            }
        }
        return byKey;
    }

    /**
     * Whether, in a collision between {@code existingPlugin} and {@code incomingPlugin}, the incoming
     * contribution is the one refused: a kernel contribution always wins; between plugins, the later one in load
     * order loses.
     */
    public boolean loserIsIncoming(String existingPlugin, String incomingPlugin) {
        if (KERNEL.equals(existingPlugin)) {
            return true;
        }
        if (KERNEL.equals(incomingPlugin)) {
            return false;
        }
        return ownership.loadIndex(incomingPlugin) >= ownership.loadIndex(existingPlugin);
    }

    /** Refuses one contribution of {@code pluginId}: an ERROR, and the plugin is marked degraded. */
    public void refuse(String pluginId, String reason) {
        log.error("plugin '{}' contribution refused: {}", pluginId, reason);
        perPlugin.computeIfAbsent(pluginId, id -> new Record()).degrade(reason);
    }

    private static String describe(Object contribution, String plugin) {
        return contribution.getClass().getName() + " (" + (KERNEL.equals(plugin) ? "kernel" : "plugin " + plugin)
                + ")";
    }

    // ---- read model --------------------------------------------------------------------------------------

    /** What has been recorded against {@code pluginId} this boot. */
    public PluginHealth health(String pluginId) {
        Record record = perPlugin.get(pluginId);
        return record == null ? PluginHealth.CLEAN : record.snapshot();
    }

    /** Every plugin id with something recorded (kernel included). */
    public Map<String, PluginHealth> all() {
        Map<String, PluginHealth> out = new LinkedHashMap<>();
        perPlugin.forEach((id, record) -> out.put(id, record.snapshot()));
        return Collections.unmodifiableMap(out);
    }

    /** The plugin id a class belongs to, if it is a plugin's. */
    public Optional<String> pluginIdOf(Class<?> type) {
        return ownership.pluginIdOf(type);
    }

    private static final class Record {
        private final AtomicLong failures = new AtomicLong();
        private volatile String lastError;
        private volatile Instant lastErrorAt;
        private final List<String> degraded = Collections.synchronizedList(new ArrayList<>());

        void failed(Throwable failure, Instant at) {
            failures.incrementAndGet();
            String message = failure.getMessage();
            lastError = failure.getClass().getSimpleName() + (message == null ? "" : ": " + truncate(message));
            lastErrorAt = at;
        }

        void degrade(String reason) {
            if (degraded.size() < 32 && !degraded.contains(reason)) {
                degraded.add(reason);
            }
        }

        PluginHealth snapshot() {
            synchronized (degraded) {
                return new PluginHealth(failures.get(), lastError, lastErrorAt, List.copyOf(degraded));
            }
        }

        private static String truncate(String text) {
            String oneLine = text.replace('\n', ' ').replace('\r', ' ');
            return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
        }
    }

    /** Rate-limit state for one (plugin, axis, key). */
    private static final class LogState {
        static final long SKIP = -1;
        static final long FIRST = -2;
        private long lastLogged;
        private long suppressed;
        private boolean logged;

        /** {@link #SKIP}, {@link #FIRST}, or the number of failures suppressed since the last logged one. */
        synchronized long claim(long nowMillis) {
            if (!logged) {
                logged = true;
                lastLogged = nowMillis;
                return FIRST;
            }
            if (nowMillis - lastLogged < REPEAT_LOG_INTERVAL_MILLIS) {
                suppressed++;
                return SKIP;
            }
            lastLogged = nowMillis;
            long count = suppressed;
            suppressed = 0;
            return count;
        }
    }
}
