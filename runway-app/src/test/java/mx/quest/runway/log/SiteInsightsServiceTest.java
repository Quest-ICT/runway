package mx.quest.runway.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import mx.quest.runway.log.SiteInsightsService.PathCount;
import mx.quest.runway.log.SiteInsightsService.Period;
import mx.quest.runway.log.SiteInsightsService.Report;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SiteInsightsService} over real day-files in a temp storage root: what counts as a 404 / a page view,
 * the exclusions, the period window and retention clamp, tenant isolation, every bound (line cap, distinct-key
 * cap, key length, line length), and the cache's three invalidation paths (same key, TTL, new day-file).
 */
class SiteInsightsServiceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @TempDir
    Path root;

    private StorageLayout layout;
    private SiteLogConfigService configs;
    private LogProperties props;
    private SiteLogService logs;
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    @BeforeEach
    void setUp() {
        StorageProperties sp = new StorageProperties();
        sp.setExternal(root);
        layout = StorageLayout.from(sp);
        configs = new SiteLogConfigService(layout, new YamlStore());
        props = new LogProperties();
        logs = new SiteLogService(layout, configs, props);
    }

    private SiteInsightsService service(long maxLines, int maxDistinct) {
        Clock clock = Clock.fixed(TODAY.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        return new SiteInsightsService(logs, configs, props, clock, maxLines, maxDistinct, Duration.ofSeconds(60),
                ticker);
    }

    private SiteInsightsService service() {
        return service(SiteInsightsService.DEFAULT_MAX_LINES, SiteInsightsService.DEFAULT_MAX_DISTINCT);
    }

    private static String line(LocalDate day, String time, String method, String path, int status) {
        return day + " " + time + "\t203.0.113.0\t" + method + "\t" + path + "\t" + status + "\n";
    }

    private void append(String siteId, LocalDate day, String... lines) throws IOException {
        Path dir = layout.siteDir(siteId).resolve("logs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("access-" + day + ".log"), String.join("", lines), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static PathCount row(List<PathCount> rows, String path) {
        return rows.stream().filter(r -> r.path().equals(path)).findFirst().orElse(null);
    }

    @Test
    void countsGetPageViewsAnd404sAndSkipsEverythingElse() throws IOException {
        append("acme", TODAY,
                line(TODAY, "08:00:00", "GET", "/", 200),
                line(TODAY, "09:00:00", "GET", "/", 200),
                line(TODAY, "10:00:00", "GET", "/about", 200),
                line(TODAY, "10:30:00", "GET", "/old.html", 200),          // .html is a page
                line(TODAY, "11:00:00", "GET", "/missing", 404),
                line(TODAY, "11:05:00", "GET", "/img/logo.png", 404),      // a missing asset IS a broken link
                line(TODAY, "11:10:00", "GET", "/missing", 404),
                line(TODAY, "11:20:00", "POST", "/contact", 200),          // not GET
                line(TODAY, "11:21:00", "HEAD", "/missing", 404),          // not GET
                line(TODAY, "11:22:00", "GET", "/static/css/site.css", 200), // asset, not a page
                line(TODAY, "11:23:00", "GET", "/robots.txt", 200),        // has an extension, not a page
                line(TODAY, "11:24:00", "GET", "/about", 304),             // revalidation, not a view
                line(TODAY, "11:25:00", "GET", "/__admin/pages", 200),     // console
                line(TODAY, "11:26:00", "GET", "/__plugin/x/static/a", 404), // plugin static
                line(TODAY, "11:27:00", "GET", "/favicon.ico", 404),       // browser probe
                line(TODAY, "11:28:00", "GET", "/apple-touch-icon.png", 404),
                line(TODAY, "11:29:00", "GET", "/.well-known/security.txt", 404),
                line(TODAY, "11:30:00", "GET", "/actuator/health", 200),
                line(TODAY, "11:31:00", "GET", "/app/x", 404),
                line(TODAY, "11:32:00", "GET", "/broken", 500));           // neither 2xx nor 404

        Report r = service().report("acme", 7);

        assertEquals(4, r.pageViews());
        assertEquals(3, r.notFound());
        assertEquals(List.of("/", "/old.html", "/about"), r.topPages().stream().map(PathCount::path).toList(),
                "count desc, then most recently seen first");
        assertEquals(2, row(r.topPages(), "/").count());
        assertEquals(TODAY + " 09:00:00", row(r.topPages(), "/").lastSeen(), "last seen is the newest line");
        assertEquals(2, row(r.topNotFound(), "/missing").count());
        assertEquals(TODAY + " 11:10:00", row(r.topNotFound(), "/missing").lastSeen());
        assertEquals(1, row(r.topNotFound(), "/img/logo.png").count());
        assertEquals(2, r.topNotFound().size());
        assertEquals(20, r.linesScanned());
        assertEquals(1, r.filesScanned());
        assertFalse(r.lineCapReached());
        assertFalse(r.approximate());
        assertTrue(r.loggingEnabled());
    }

    @Test
    void malformedOverlongAndUnterminatedLinesAreIgnored() throws IOException {
        String overlong = line(TODAY, "08:00:00", "GET", "/" + "x".repeat(SiteInsightsService.MAX_LINE_BYTES), 404);
        append("acme", TODAY,
                "garbage without tabs\n",
                "2026-10-17 08:00:00\tip\tGET\t/too\tmany\t404\n",
                "2026-10-17 08:00:00\tip\tGET\t/nostatus\tabc\n",
                "2026-10-17 08:00:00\tip\tGET\tnot-a-path\t404\n",
                "\n",
                overlong,
                line(TODAY, "09:00:00", "GET", "/ok", 404),
                "2026-10-17 10:00:00\tip\tGET\t/partial\t404"); // no newline: the writer may be mid-append

        Report r = service().report("acme", 7);

        assertEquals(List.of("/ok"), r.topNotFound().stream().map(PathCount::path).toList());
        assertEquals(1, r.notFound());
    }

    @Test
    void theWindowCoversTheRequestedDaysIncludingToday() throws IOException {
        append("acme", TODAY, line(TODAY, "08:00:00", "GET", "/today", 404));
        LocalDate sixAgo = TODAY.minusDays(6);
        LocalDate sevenAgo = TODAY.minusDays(7);
        LocalDate twentyNineAgo = TODAY.minusDays(29);
        LocalDate thirtyAgo = TODAY.minusDays(30);
        append("acme", sixAgo, line(sixAgo, "08:00:00", "GET", "/six", 404));
        append("acme", sevenAgo, line(sevenAgo, "08:00:00", "GET", "/seven", 404));
        append("acme", twentyNineAgo, line(twentyNineAgo, "08:00:00", "GET", "/twentynine", 404));
        append("acme", thirtyAgo, line(thirtyAgo, "08:00:00", "GET", "/thirty", 404));
        append("acme", TODAY.plusDays(1), line(TODAY.plusDays(1), "08:00:00", "GET", "/future", 404));

        Report week = service().report("acme", 7);
        assertEquals(List.of("/today", "/six"), week.topNotFound().stream().map(PathCount::path).toList());
        assertEquals(sixAgo, week.from());
        assertEquals(TODAY, week.to());
        assertEquals(7, week.days());

        Report month = service().report("acme", 30);
        assertEquals(4, month.notFound(), "today, -6, -7, -29; never -30 or a future-dated file");
        assertTrue(month.topNotFound().stream().noneMatch(p -> p.path().equals("/thirty")
                || p.path().equals("/future")));
    }

    @Test
    void anUnknownPeriodFallsBackToTheDefaultNeverToTheRequestedValue() {
        assertEquals(7, SiteInsightsService.normalizePeriod(null));
        assertEquals(7, SiteInsightsService.normalizePeriod(365));
        assertEquals(7, SiteInsightsService.normalizePeriod(-1));
        assertEquals(30, SiteInsightsService.normalizePeriod(30));
        assertEquals(7, service().report("acme", 100_000).days());
    }

    @Test
    void periodsAreClampedToTheSitesRetentionAndDeduplicated() throws IOException {
        assertEquals(List.of(new Period(7, 7), new Period(30, 30)), service().periods("acme"));

        configs.save("acme", new SiteLogConfig(null, 10));
        assertEquals(List.of(new Period(7, 7), new Period(30, 10)), service().periods("acme"));
        assertEquals(10, service().report("acme", 30).days());

        configs.save("acme", new SiteLogConfig(null, 3));
        assertEquals(List.of(new Period(7, 3)), service().periods("acme"));
        Report r = service().report("acme", 30);
        assertEquals(3, r.days());
        assertEquals(TODAY.minusDays(2), r.from());

        props.setMaxRetentionDays(5);
        configs.save("acme", new SiteLogConfig(null, 60));
        assertEquals(5, service().retentionDays("acme"), "the platform cap wins over the site's preference");
    }

    @Test
    void aReportReadsOnlyItsOwnSitesLogs() throws IOException {
        append("acme", TODAY, line(TODAY, "08:00:00", "GET", "/acme-page", 200));
        append("beta", TODAY, line(TODAY, "08:00:00", "GET", "/beta-secret", 404),
                line(TODAY, "08:00:00", "GET", "/beta-page", 200));

        Report acme = service().report("acme", 7);
        assertEquals(List.of("/acme-page"), acme.topPages().stream().map(PathCount::path).toList());
        assertTrue(acme.topNotFound().isEmpty());

        assertTrue(service().report("../beta", 7).topNotFound().isEmpty(), "an unsafe id resolves to nothing");
        assertTrue(service().report("nosuchsite", 7).topPages().isEmpty());
    }

    @Test
    void theLineCapStopsTheScanAndKeepsTheNewestDays() throws IOException {
        LocalDate yesterday = TODAY.minusDays(1);
        append("acme", yesterday,
                line(yesterday, "08:00:00", "GET", "/old-a", 404),
                line(yesterday, "08:00:01", "GET", "/old-b", 404));
        append("acme", TODAY,
                line(TODAY, "08:00:00", "GET", "/new-a", 404),
                line(TODAY, "08:00:01", "GET", "/new-b", 404),
                line(TODAY, "08:00:02", "GET", "/new-c", 404));

        Report r = service(4, 100).report("acme", 7);

        assertTrue(r.lineCapReached());
        assertEquals(4, r.linesScanned());
        assertEquals(4, r.notFound());
        assertTrue(r.topNotFound().stream().anyMatch(p -> p.path().equals("/new-c")), "newest file read first");
        assertTrue(r.topNotFound().stream().noneMatch(p -> p.path().equals("/old-b")));

        Report exact = service(5, 100).report("acme", 7);
        assertFalse(exact.lineCapReached(), "exactly cap-many lines is a complete scan, not a truncated one");
    }

    @Test
    void theDistinctCapBoundsMemoryAndKeepsTheFrequentPaths() throws IOException {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            lines.append(line(TODAY, "08:00:0" + i, "GET", "/popular", 404));
        }
        for (int i = 0; i < 100; i++) {
            lines.append(line(TODAY, "09:00:00", "GET", "/scan-" + i, 404));
        }
        append("acme", TODAY, lines.toString());

        Report r = service(1_000, 10).report("acme", 7);

        assertTrue(r.approximate());
        assertEquals(105, r.notFound(), "the total stays exact even when rare paths are dropped");
        assertTrue(r.topNotFound().size() <= 10);
        assertEquals("/popular", r.topNotFound().get(0).path());
        assertEquals(5, r.topNotFound().get(0).count());
    }

    @Test
    void theTallyNeverHoldsMoreThanItsCapacity() {
        SiteInsightsService.Tally tally = new SiteInsightsService.Tally(8);
        for (int i = 0; i < 1_000; i++) {
            tally.add("/p" + i, "2026-10-17 00:00:00");
            assertTrue(tally.size() <= 8);
        }
        assertTrue(tally.pruned);
        assertEquals(1_000, tally.total);
    }

    @Test
    void aVeryLongPathIsCutBeforeItBecomesAKey() throws IOException {
        String longPath = "/" + "a".repeat(2_000);
        append("acme", TODAY, line(TODAY, "08:00:00", "GET", longPath, 404),
                line(TODAY, "08:00:01", "GET", longPath + "-variant", 404));

        Report r = service().report("acme", 7);

        PathCount only = r.topNotFound().get(0);
        assertEquals(SiteInsightsService.MAX_KEY_CHARS + 1, only.path().length());
        assertTrue(only.path().endsWith("…"));
        assertEquals(2, only.count(), "two paths identical up to the cap fold into one key");
    }

    @Test
    void capKeyNeverSplitsASurrogatePair() {
        String emoji = "😀";
        String path = "/" + "a".repeat(SiteInsightsService.MAX_KEY_CHARS - 2) + emoji + "tail";
        // The emoji's high surrogate sits at the last kept index, so the cut backs off one char instead of
        // keeping half a code point.
        assertEquals("/" + "a".repeat(SiteInsightsService.MAX_KEY_CHARS - 2) + "…", SiteInsightsService.capKey(path));
        assertEquals("/short", SiteInsightsService.capKey("/short"));
    }

    @Test
    void aRepeatRequestIsServedFromTheCacheUntilTheTtlExpires() throws IOException {
        append("acme", TODAY, line(TODAY, "08:00:00", "GET", "/a", 404));
        SiteInsightsService service = service();
        assertEquals(1, service.report("acme", 7).notFound());

        append("acme", TODAY, line(TODAY, "08:01:00", "GET", "/a", 404));
        assertEquals(1, service.report("acme", 7).notFound(), "same site/period/day-files within the TTL: cached");

        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        assertEquals(2, service.report("acme", 7).notFound(), "after the TTL today's growth shows");
    }

    @Test
    void aNewDayFileInvalidatesTheCacheImmediately() throws IOException {
        LocalDate yesterday = TODAY.minusDays(1);
        append("acme", yesterday, line(yesterday, "08:00:00", "GET", "/a", 404));
        SiteInsightsService service = service();
        assertEquals(1, service.report("acme", 7).notFound());

        append("acme", TODAY, line(TODAY, "00:00:01", "GET", "/b", 404));
        assertEquals(2, service.report("acme", 7).notFound(), "a new day-file is a new cache key");
    }

    @Test
    void thePeriodsAndSitesAreCachedSeparately() throws IOException {
        LocalDate tenAgo = TODAY.minusDays(10);
        append("acme", tenAgo, line(tenAgo, "08:00:00", "GET", "/old", 404));
        append("beta", TODAY, line(TODAY, "08:00:00", "GET", "/b", 404));
        SiteInsightsService service = service();
        assertEquals(0, service.report("acme", 7).notFound());
        assertEquals(1, service.report("acme", 30).notFound());
        assertEquals(1, service.report("beta", 7).notFound());
        assertEquals(0, service.report("acme", 7).notFound());
    }

    @Test
    void withLoggingOffTheReportSaysSoAndReadsNothing() throws IOException {
        append("acme", TODAY, line(TODAY, "08:00:00", "GET", "/a", 404));
        props.setEnabled(false);

        Report r = service().report("acme", 7);

        assertFalse(r.loggingEnabled());
        assertEquals(0, r.notFound());
        assertEquals(0, r.filesScanned());
    }

    @Test
    void pageLikeAndExclusionHeuristics() {
        assertTrue(SiteInsightsService.isPageLike("/"));
        assertTrue(SiteInsightsService.isPageLike("/es/acerca"));
        assertTrue(SiteInsightsService.isPageLike("/docs/v1.2/intro")); // a dot in a parent segment is fine
        assertTrue(SiteInsightsService.isPageLike("/page.HTML"));
        assertFalse(SiteInsightsService.isPageLike("/sitemap.xml"));
        assertFalse(SiteInsightsService.isPageLike("/static/doc/guide"));
        assertTrue(SiteInsightsService.isExcluded("/__health"));
        assertTrue(SiteInsightsService.isExcluded("/favicon-32.png"));
        assertFalse(SiteInsightsService.isExcluded("/apps"));
        assertFalse(SiteInsightsService.isExcluded("/_drafts"));
    }
}
