package mx.quest.runway.bandwidth;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Meters each site's bandwidth. {@link #record} is the hot path — it only bumps in-memory {@link LongAdder}s
 * keyed by site and UTC hour, doing no I/O. A single daemon thread snapshots the counters to
 * {@code sites/<id>/stats/bandwidth.csv} periodically (crash-survival; the CSV is reloaded at startup) and
 * prunes buckets past the retention window. Metering is <b>aggregate byte counts only</b> — never request
 * contents — which is what lets it (in aggregate) cross to the app realm without breaking tenant isolation.
 */
@Service
public class BandwidthService {

    private static final Logger log = LoggerFactory.getLogger(BandwidthService.class);
    private static final long SECONDS_PER_HOUR = 3600;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Mutable per-hour counters. */
    private static final class Counters {
        final LongAdder in = new LongAdder();
        final LongAdder out = new LongAdder();
        final LongAdder requests = new LongAdder();
    }

    /** One point in a report series. */
    public record BandPoint(String label, long bytesIn, long bytesOut) {
        public String inHuman() {
            return Bytes.human(bytesIn);
        }

        public String outHuman() {
            return Bytes.human(bytesOut);
        }

        /** The out-bytes bar width as a whole-number percent of {@code max} (for a CSS bar). */
        public long outBar(long max) {
            return max <= 0 ? 0 : Math.min(100, bytesOut * 100 / max);
        }
    }

    /** A site's rolled-up totals, for the app-realm aggregate. */
    public record SiteBandwidth(String siteId, long bytesIn, long bytesOut) {
        public String inHuman() {
            return Bytes.human(bytesIn);
        }

        public String outHuman() {
            return Bytes.human(bytesOut);
        }
    }

    /** A tenant report: the daily series plus headline totals. */
    public record BandwidthReport(List<BandPoint> daily, long in24h, long out24h, long in7d, long out7d,
                                  long in30d, long out30d) {
        public long maxDaily() {
            return Math.max(1, daily.stream().mapToLong(BandPoint::bytesOut).max().orElse(1));
        }

        public String in24hHuman() {
            return Bytes.human(in24h);
        }

        public String out24hHuman() {
            return Bytes.human(out24h);
        }

        public String out7dHuman() {
            return Bytes.human(out7d);
        }

        public String out30dHuman() {
            return Bytes.human(out30d);
        }
    }

    private final StorageLayout layout;
    private final BandwidthProperties props;
    private final Map<String, Map<Long, Counters>> counters = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile LocalDate lastPrune;
    private volatile java.util.Set<String> overQuota = java.util.Set.of();
    private Thread worker;
    /** Each site's cap in bytes ({@code 0} = unlimited). */
    private final ToLongFunction<String> quotaFor;

    /** With the caps bound from configuration only (tests). */
    public BandwidthService(StorageLayout layout, BandwidthProperties props) {
        this(layout, props, props.getQuota()::effectiveFor);
    }

    /** With each site's cap from {@code quotas}: the host's, else the one set on the Sites screen, else the default. */
    @Autowired
    public BandwidthService(StorageLayout layout, BandwidthProperties props, SiteBandwidthQuotas quotas) {
        this(layout, props, quotas::bytesFor);
    }

    private BandwidthService(StorageLayout layout, BandwidthProperties props, ToLongFunction<String> quotaFor) {
        this.layout = layout;
        this.props = props;
        this.quotaFor = quotaFor;
    }

    /** Adds one request's byte counts to the current hour's bucket for {@code siteId}. Hot path — no I/O. */
    public void record(String siteId, long bytesIn, long bytesOut) {
        if (!props.isEnabled() || siteId == null) {
            return;
        }
        Counters c = counters.computeIfAbsent(siteId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(currentHour(), h -> new Counters());
        c.in.add(Math.max(0, bytesIn));
        c.out.add(Math.max(0, bytesOut));
        c.requests.increment();
    }

    /**
     * Whether {@code siteId} has exceeded its effective rolling-30-day outbound quota. O(1) — reads a set
     * that is recomputed on the snapshot cadence, so it never runs the 30-day sum on the request hot path.
     * Always {@code false} when no quota is configured, and when metering is off.
     */
    public boolean isOverQuota(String siteId) {
        // A cap is measured by metering: with metering off (runway.bandwidth.enabled=false) nothing is counted, so the
        // set computed at startup from the saved totals would never change again and a site over its cap then would
        // answer 503 for good. No metering, no cap.
        return props.isEnabled() && siteId != null && overQuota.contains(siteId);
    }

    /**
     * Recomputes the over-quota set from the current 30-day outbound totals. Called once at startup and on
     * each snapshot cycle. A site with no configured cap (effective {@code 0}) is never included.
     */
    void recomputeOverQuota() {
        java.util.Set<String> over = new java.util.HashSet<>();
        for (SiteBandwidth s : aggregate()) {
            long cap = quotaFor.applyAsLong(s.siteId());
            if (cap > 0 && s.bytesOut() > cap) {
                over.add(s.siteId());
            }
        }
        overQuota = over.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(over);
    }

    /**
     * Re-evaluates which sites are over their cap now, rather than at the next snapshot: after the operator changes a
     * site's cap, so raising it reopens the site at once.
     */
    public void refreshQuotas() {
        recomputeOverQuota();
    }

    @PostConstruct
    void start() {
        reloadAll();
        recomputeOverQuota();
        if (!props.isEnabled()) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "runway-bandwidth");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Stops the daemon and takes a final snapshot. <b>Best-effort:</b> counts recorded by a {@code record()}
     * that lands after this final {@link #snapshotAll()} are lost — bandwidth metering is coarse telemetry and
     * the CSV snapshot is itself crash-safe, so a fully-quiesced count is not worth blocking the hot path for.
     */
    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        snapshotAll(); // final flush after the worker exits (best-effort — see the method contract)
    }

    /** The last-{@code days} daily series and 24h/7d/30d totals for one site. */
    public BandwidthReport report(String siteId, int days) {
        Map<Long, Counters> hours = counters.getOrDefault(siteId, Map.of());
        long nowHour = currentHour();
        LocalDate today = LocalDate.now(ZONE);

        List<BandPoint> daily = new ArrayList<>();
        for (int d = days - 1; d >= 0; d--) {
            LocalDate day = today.minusDays(d);
            long in = 0;
            long out = 0;
            for (int h = 0; h < 24; h++) {
                long hourEpoch = day.atStartOfDay(ZONE).toEpochSecond() / SECONDS_PER_HOUR + h;
                Counters c = hours.get(hourEpoch);
                if (c != null) {
                    in += c.in.sum();
                    out += c.out.sum();
                }
            }
            daily.add(new BandPoint(DAY_LABEL.format(day), in, out));
        }
        return new BandwidthReport(daily,
                total(hours, nowHour - 23, nowHour, true), total(hours, nowHour - 23, nowHour, false),
                total(hours, nowHour - 24 * 7 + 1, nowHour, true), total(hours, nowHour - 24 * 7 + 1, nowHour, false),
                total(hours, nowHour - 24 * 30 + 1, nowHour, true), total(hours, nowHour - 24 * 30 + 1, nowHour, false));
    }

    /** Per-site 30-day totals, busiest first — for the app-realm aggregate view. */
    public List<SiteBandwidth> aggregate() {
        long nowHour = currentHour();
        long from = nowHour - 24 * 30 + 1;
        List<SiteBandwidth> out = new ArrayList<>();
        for (Map.Entry<String, Map<Long, Counters>> site : counters.entrySet()) {
            out.add(new SiteBandwidth(site.getKey(),
                    total(site.getValue(), from, nowHour, true), total(site.getValue(), from, nowHour, false)));
        }
        out.sort(Comparator.comparingLong((SiteBandwidth s) -> s.bytesOut()).reversed());
        return out;
    }

    private static long total(Map<Long, Counters> hours, long fromHour, long toHour, boolean in) {
        long sum = 0;
        for (Map.Entry<Long, Counters> e : hours.entrySet()) {
            if (e.getKey() >= fromHour && e.getKey() <= toHour) {
                sum += in ? e.getValue().in.sum() : e.getValue().out.sum();
            }
        }
        return sum;
    }

    private static long currentHour() {
        return Instant.now().getEpochSecond() / SECONDS_PER_HOUR;
    }

    private void loop() {
        while (running) {
            try {
                TimeUnit.SECONDS.sleep(Math.max(1, props.getSnapshotSeconds()));
                snapshotAll();
                pruneIfDue();
                recomputeOverQuota();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("bandwidth snapshot failed: {}", e.toString());
            }
        }
    }

    void snapshotAll() {
        long cutoff = currentHour() - (long) props.getRetentionDays() * 24;
        for (Map.Entry<String, Map<Long, Counters>> site : counters.entrySet()) {
            try {
                snapshotSite(site.getKey(), site.getValue(), cutoff);
            } catch (IOException | RuntimeException e) {
                log.warn("bandwidth snapshot failed for '{}': {}", site.getKey(), e.toString());
            }
        }
    }

    private void snapshotSite(String siteId, Map<Long, Counters> hours, long cutoff) throws IOException {
        StringBuilder csv = new StringBuilder();
        hours.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (e.getKey() >= cutoff) {
                Counters c = e.getValue();
                csv.append(e.getKey()).append(',').append(c.in.sum()).append(',').append(c.out.sum())
                        .append(',').append(c.requests.sum()).append('\n');
            }
        });
        Path file = statsFile(siteId);
        if (file == null) {
            return;
        }
        AtomicFiles.write(file, csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    void reloadAll() {
        Path sites = layout.sites();
        if (!Files.isDirectory(sites)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(sites)) {
            dirs.filter(Files::isDirectory).forEach(dir -> reloadSite(dir.getFileName().toString()));
        } catch (IOException e) {
            log.warn("bandwidth reload scan failed: {}", e.toString());
        }
    }

    private void reloadSite(String siteId) {
        Path file = statsFile(siteId);
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Map<Long, Counters> hours = new ConcurrentHashMap<>();
        try {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    Counters c = new Counters();
                    c.in.add(Long.parseLong(parts[1].trim()));
                    c.out.add(Long.parseLong(parts[2].trim()));
                    if (parts.length >= 4) {
                        c.requests.add(Long.parseLong(parts[3].trim()));
                    }
                    hours.put(Long.parseLong(parts[0].trim()), c);
                }
            }
            if (!hours.isEmpty()) {
                counters.put(siteId, hours);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not reload bandwidth for '{}': {}", siteId, e.toString());
        }
    }

    private void pruneIfDue() {
        LocalDate today = LocalDate.now(ZONE);
        if (today.equals(lastPrune)) {
            return;
        }
        lastPrune = today;
        long cutoff = currentHour() - (long) props.getRetentionDays() * 24;
        counters.values().forEach(hours -> hours.keySet().removeIf(hour -> hour < cutoff));
    }

    private Path statsFile(String siteId) {
        try {
            return layout.siteDir(siteId).resolve("stats").resolve("bandwidth.csv");
        } catch (IllegalArgumentException unsafe) {
            return null;
        }
    }
}
