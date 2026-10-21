package mx.quest.protection;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CidrSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mutable set of currently-blocked IPs and CIDRs consulted on <b>every</b> request by the DoS floor
 * ({@link RateLimitEdgeGuard}), plus the auto-escalation target of {@link TokenBucketRateLimiter}. It is the
 * platform-realm abuse blocklist (never per-tenant): a directly-reachable abuser is turned away before the
 * security chain.
 *
 * <h2>Manual vs. auto blocks — two lifecycles</h2>
 * <ul>
 *   <li><b>Manual</b> blocks (an operator's deliberate ban from the {@code /__app/dos} screen) are
 *       <b>persisted</b> to {@code external/blocklist.yml} so they survive a restart. Only manual blocks are
 *       ever written; the file is rewritten on every manual change (block/unblock/prune of a manual entry).</li>
 *   <li><b>Auto</b> blocks (a rate-limit breach escalated by {@link TokenBucketRateLimiter}) are
 *       <b>in-memory only</b> — ephemeral by design: they expire by their own timer and are re-earned by a
 *       still-abusive IP after a restart, so a transient flood never leaves a stale ban on disk.</li>
 * </ul>
 *
 * <h2>Allowlist / loopback — never blockable</h2>
 * Loopback ({@code 127.0.0.0/8}, {@code ::1}) and the configured allowlist CIDRs are <b>always allowlisted</b>:
 * {@link #isAllowlisted(String)} short-circuits {@link #isBlocked(String)} (an allowlisted IP is never reported
 * blocked even if an entry somehow names it), and {@link #block} refuses an allowlisted target outright. This is
 * the safety rail against auto-blocking a health probe, a trusted proxy, or the operator's own loopback.
 *
 * <h2>Hot-path cost</h2>
 * {@link #isBlocked(String)} is O(1) for the common exact-IP case (a single {@link ConcurrentHashMap} lookup and
 * a lazy expiry prune of just that entry — never a full scan), plus a membership test against a small
 * {@link CidrSet} rebuilt only when a CIDR-form entry is added/removed (rare). The allowlist check is likewise a
 * cheap loopback test + small {@link CidrSet} lookup. Fail-<b>open</b>: any fault in the check path returns "not
 * blocked" so a blocklist bug can never turn away legitimate traffic — the rate-limit floor still applies.
 *
 * <p>Thread-safe (a lock-free map on the hot path; manual persistence serialized through {@link YamlStore}).
 */
public final class IpBlocklist {

    private static final Logger log = LoggerFactory.getLogger(IpBlocklist.class);

    private final Path file;
    private final YamlStore yaml;
    private final CidrSet allowlist;

    /** All active blocks (manual + auto), keyed by the exact IP/CIDR string. Lock-free on the read hot path. */
    private final ConcurrentHashMap<String, Entry> blocks = new ConcurrentHashMap<>();

    /** The CIDR-form blocked entries only, rebuilt on mutation (rare); an exact IP is matched by the map above. */
    private volatile CidrSet cidrBlocks = CidrSet.of(List.of());

    /** Injectable epoch-seconds clock so expiry is deterministically testable. */
    private final java.util.function.LongSupplier nowSeconds;

    /**
     * @param layout    the kernel storage layout (yields {@code external/blocklist.yml})
     * @param yaml      the shared YAML store (atomic, per-file-serialized writes)
     * @param allowlist the configured trusted CIDRs — never blockable (loopback is always added on top)
     */
    public IpBlocklist(StorageLayout layout, YamlStore yaml, CidrSet allowlist) {
        this(layout.blocklistFile(), yaml, allowlist, () -> System.currentTimeMillis() / 1000L);
    }

    /** Full constructor with an injectable clock, for tests. */
    IpBlocklist(Path file, YamlStore yaml, CidrSet allowlist, java.util.function.LongSupplier nowSeconds) {
        this.file = file;
        this.yaml = yaml;
        this.allowlist = allowlist != null ? allowlist : CidrSet.of(List.of());
        this.nowSeconds = nowSeconds;
        loadManual();
        rebuildCidrIndex();
    }

    /**
     * Whether {@code ip} is on the never-block allowlist: a loopback address ({@code 127.0.0.0/8} / {@code ::1})
     * or a member of the configured allowlist CIDRs. Fail-safe — a malformed literal is simply not allowlisted.
     */
    public boolean isAllowlisted(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (isLoopback(ip)) {
            return true;
        }
        return allowlist.contains(ip);
    }

    /**
     * Whether {@code ip} is currently blocked. O(1) for an exact-IP hit (with a lazy prune if that one entry just
     * expired), then a membership test against the small CIDR index. An allowlisted IP is <b>never</b> blocked.
     * Fail-<b>open</b>: any fault returns {@code false} (the rate-limit floor still applies).
     */
    public boolean isBlocked(String ip) {
        return blockKind(ip) != null;
    }

    /**
     * The same decision as {@link #isBlocked}, but reporting <b>which kind</b> of block matched — or
     * {@code null} when the address is not blocked.
     *
     * <p>Exists because a bare {@code 403} is not diagnosable. An operator seeing a rejection needs to know
     * whether <i>they</i> banned the address or whether the system escalated it after repeated throttling:
     * those look identical on the wire and call for opposite responses. The returned token is short and closed
     * ({@code blocklist-manual}, {@code blocklist-auto}, {@code blocklist-cidr}) because it becomes both a log
     * field and a metric tag.
     *
     * <p>{@code blocklist-cidr} does not distinguish manual from auto: the CIDR index answers membership only,
     * and widening it to carry entries would add work to the hot path for a case that is rare and already
     * greppable in {@code blocklist.yml}. Stated here rather than silently conflated.
     *
     * <p>{@link #isBlocked} delegates to this so the two can never disagree — the fail-open contract, the
     * allowlist precedence and the lazy prune all live in one place.
     */
    public String blockKind(String ip) {
        Match match = match(ip);
        return match == null ? null : match.kind();
    }

    /**
     * A matched block: which kind it is, and how long it still has to run.
     *
     * @param kind             {@code blocklist-manual}, {@code blocklist-auto} or {@code blocklist-cidr}
     * @param remainingSeconds seconds until the block lifts, or {@code 0} when it is indefinite
     */
    public record Match(String kind, long remainingSeconds) {

        /** Whether this block was escalated by the rate limiter rather than decided by an operator. */
        public boolean autoEscalated() {
            return "blocklist-auto".equals(kind);
        }
    }

    /**
     * {@link #blockKind} plus the block's <b>remaining duration</b> — everything the edge needs to answer.
     *
     * <p>The duration is what turns a rejection into a usable signal. An auto-block is the rate limiter
     * escalating, so it can honestly say "come back in N seconds"; an operator ban cannot, and should not
     * pretend to. Reading the remaining time here rather than at the call site keeps it consistent with the
     * expiry decision made three lines above it — computing it separately would let the two disagree at a
     * block's final second, which is precisely when it matters.
     */
    public Match match(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            if (isAllowlisted(ip)) {
                return null;
            }
            long now = nowSeconds.getAsLong();
            Entry exact = blocks.get(ip);
            if (exact != null) {
                if (exact.isExpired(now)) {
                    remove(ip); // lazy prune of just this entry — no scan on the hot path
                } else {
                    long remaining = exact.indefinite() ? 0 : Math.max(1, exact.untilEpochSeconds() - now);
                    return new Match(exact.manual() ? "blocklist-manual" : "blocklist-auto", remaining);
                }
            }
            return cidrBlocks.contains(ip) ? new Match("blocklist-cidr", 0) : null;
        } catch (RuntimeException failOpen) {
            log.warn("blocklist check failed for {} — failing open (rate-limit floor still applies): {}",
                    ip, failOpen.toString());
            return null;
        }
    }

    /**
     * Blocks {@code ipOrCidr} for {@code seconds} (a value {@code <= 0} means an indefinite block). An
     * <b>allowlisted</b> target is refused (returns {@code false}) — loopback and trusted CIDRs are unblockable.
     * A <b>manual</b> block is persisted to {@code blocklist.yml}; an <b>auto</b> block stays in memory only.
     *
     * @param ipOrCidr the IP or CIDR to ban (trimmed)
     * @param seconds  the ban duration in seconds ({@code <= 0} ⇒ indefinite)
     * @param reason   a short human-readable reason (shown on the DoS screen)
     * @param manual   {@code true} for an operator block (persisted), {@code false} for an auto-escalated one
     * @return {@code true} if the block was applied, {@code false} if the target was refused (allowlisted/blank)
     */
    public boolean block(String ipOrCidr, long seconds, String reason, boolean manual) {
        if (ipOrCidr == null || ipOrCidr.isBlank()) {
            return false;
        }
        String key = ipOrCidr.trim();
        if (isAllowlisted(key)) {
            return false; // never block loopback or an allowlisted CIDR (safety rail)
        }
        long until = seconds <= 0 ? 0L : nowSeconds.getAsLong() + seconds;
        blocks.put(key, new Entry(key, reason == null ? "" : reason, until, manual));
        if (isCidr(key)) {
            rebuildCidrIndex();
        }
        if (manual) {
            persistManual();
        }
        return true;
    }

    /** Removes {@code ipOrCidr} from the blocklist (rewriting the file if a manual entry was removed). */
    public void unblock(String ipOrCidr) {
        if (ipOrCidr == null) {
            return;
        }
        remove(ipOrCidr.trim());
    }

    /**
     * The active blocks, expired entries pruned, ordered by expiry (indefinite first, then latest expiry). A
     * snapshot copy — safe to render without holding any lock.
     */
    public List<Entry> list() {
        long now = nowSeconds.getAsLong();
        // Prune expired entries first (this may rewrite the file if a manual entry lapsed).
        List<String> expired = new ArrayList<>();
        for (Entry e : blocks.values()) {
            if (e.isExpired(now)) {
                expired.add(e.ip());
            }
        }
        expired.forEach(this::remove);
        List<Entry> active = new ArrayList<>(blocks.values());
        // Indefinite (until == 0) sorts first; otherwise newest expiry first.
        active.sort(Comparator.comparingLong((Entry e) -> e.indefinite() ? Long.MAX_VALUE : e.untilEpochSeconds())
                .reversed());
        return active;
    }

    private void remove(String key) {
        Entry prev = blocks.remove(key);
        if (prev != null) {
            recordLapsed(prev);
        }
        if (isCidr(key)) {
            rebuildCidrIndex();
        }
        if (prev != null && prev.manual()) {
            persistManual();
        }
    }

    private void rebuildCidrIndex() {
        cidrBlocks = CidrSet.of(blocks.keySet().stream().filter(IpBlocklist::isCidr).toList());
    }

    // ---------- persistence (manual blocks only) --------------------------------------------------------

    /**
     * Seeds the in-memory set from the persisted manual blocks at startup. A missing/malformed file yields an
     * empty set (fail-soft — a corrupt blocklist must never block boot); already-expired persisted entries are
     * dropped.
     */
    private void loadManual() {
        StoredBlocklist stored;
        try {
            stored = yaml.read(file, StoredBlocklist.class).orElse(null);
        } catch (IOException | RuntimeException e) {
            log.warn("could not read {} — starting with no persisted blocks: {}", file, e.toString());
            return;
        }
        if (stored == null || stored.getEntries() == null) {
            return;
        }
        long now = nowSeconds.getAsLong();
        for (StoredBlocklist.StoredEntry s : stored.getEntries()) {
            if (s == null || s.getIp() == null || s.getIp().isBlank()) {
                continue;
            }
            Entry e = new Entry(s.getIp().trim(), s.getReason() == null ? "" : s.getReason(),
                    s.getUntilEpochSeconds(), true); // everything on disk is manual by construction
            if (!e.isExpired(now)) {
                blocks.put(e.ip(), e);
            }
        }
    }

    /** Rewrites {@code blocklist.yml} with the current manual blocks (auto blocks are never persisted). */
    private void persistManual() {
        List<StoredBlocklist.StoredEntry> manual = new ArrayList<>();
        for (Entry e : blocks.values()) {
            if (e.manual()) {
                manual.add(new StoredBlocklist.StoredEntry(e.ip(), e.reason(), e.untilEpochSeconds()));
            }
        }
        try {
            yaml.write(file, new StoredBlocklist(manual));
        } catch (IOException | RuntimeException e) {
            // A persistence failure must not break the live in-memory block; log and carry on.
            log.warn("could not persist manual blocks to {}: {}", file, e.toString());
        }
    }

    // ---------- helpers ---------------------------------------------------------------------------------

    private static boolean isCidr(String key) {
        return key != null && key.indexOf('/') >= 0;
    }

    /** Whether {@code ip} is a loopback literal ({@code 127.0.0.0/8} or {@code ::1}); DNS-free, fail-safe. */
    private static boolean isLoopback(String ip) {
        String text = stripZone(ip);
        if (text.isEmpty()) {
            return false;
        }
        try {
            return InetAddress.ofLiteral(text).isLoopbackAddress(); // literal only — never resolves DNS
        } catch (RuntimeException notALiteral) {
            return false;
        }
    }

    private static String stripZone(String ip) {
        String text = ip.trim();
        int pct = text.indexOf('%');
        return pct < 0 ? text : text.substring(0, pct);
    }

    /**
     * One active block. {@code untilEpochSeconds == 0} is an indefinite block; {@code manual} distinguishes a
     * persisted operator ban from an ephemeral auto-escalated one.
     *
     * @param ip                the blocked IP or CIDR
     * @param reason            a short human-readable reason
     * @param untilEpochSeconds the epoch-second the block lapses ({@code 0} ⇒ indefinite)
     * @param manual            {@code true} for a persisted operator block, {@code false} for an auto block
     */
    public record Entry(String ip, String reason, long untilEpochSeconds, boolean manual) {

        /** Whether this block has lapsed at {@code nowEpochSeconds} (an indefinite block never expires). */
        public boolean isExpired(long nowEpochSeconds) {
            return untilEpochSeconds != 0 && untilEpochSeconds <= nowEpochSeconds;
        }

        /** Whether this is an indefinite block (no expiry). */
        public boolean indefinite() {
            return untilEpochSeconds == 0;
        }
    }

    // --- history of blocks that have ended ------------------------------------------------------------

    /** How many ended blocks are kept for the operator screen. Bounded: this is a diagnostic, not an audit log. */
    private static final int HISTORY_LIMIT = 100;

    /** Newest-first ring of blocks that have ended. Guarded by itself; only ever touched off the hot path. */
    private final java.util.Deque<Lapsed> history = new java.util.ArrayDeque<>();

    /**
     * A block that is no longer in force — expired on its own, or lifted by an operator.
     *
     * @param ip       the address or CIDR that was blocked
     * @param reason   the reason recorded when it was applied
     * @param manual   whether an operator applied it (as opposed to the limiter escalating)
     * @param endedAt  epoch seconds when it stopped being enforced
     */
    public record Lapsed(String ip, String reason, boolean manual, long endedAt) {
    }

    /**
     * Remembers a block that has just ended, so it leaves a trace on the operator screen.
     *
     * <h2>Why this exists</h2>
     * An auto-block lasts fifteen minutes by default. An operator investigating "the site was slow around
     * three o'clock" arrives long after, sees an empty blocklist, and has no way to tell whether the guard
     * acted at all — the evidence deleted itself at the exact rate that makes it hardest to catch. The
     * rejection counter says a rejection happened; only this says <b>who</b> and <b>why</b>.
     *
     * <p><b>In memory, bounded, per node, and lost on restart</b> — deliberately. Persisting it would turn a
     * flood into unbounded disk writes at precisely the moment the guard is trying to be cheap, and would
     * store attacker-supplied addresses indefinitely. This is a diagnostic that answers "what happened
     * recently", not an audit trail; the {@code runway.protect} log is where a durable record lives.
     */
    private void recordLapsed(Entry ended) {
        synchronized (history) {
            history.addFirst(new Lapsed(ended.ip(), ended.reason(), ended.manual(), nowSeconds.getAsLong()));
            while (history.size() > HISTORY_LIMIT) {
                history.removeLast();
            }
        }
    }

    /**
     * Blocks that have ended since this node started, newest first (at most {@value #HISTORY_LIMIT}).
     *
     * <p>Call {@link #list()} first if you want the history current: expiry is lazy, so a block that has run
     * out is recorded as ended at the moment something looks, not at the moment its timer ran down.
     */
    public List<Lapsed> recentlyEnded() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }
}
