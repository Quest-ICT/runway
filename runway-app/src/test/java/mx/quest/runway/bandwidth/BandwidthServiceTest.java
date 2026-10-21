package mx.quest.runway.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.bandwidth.BandwidthService.BandwidthReport;
import mx.quest.runway.bandwidth.BandwidthService.SiteBandwidth;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The metering engine: record→report, human sizes, CSV snapshot/reload, per-site aggregate. */
class BandwidthServiceTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private BandwidthProperties props;

    @BeforeEach
    void setUp() {
        StorageProperties sp = new StorageProperties();
        sp.setExternal(external);
        layout = StorageLayout.from(sp);
        props = new BandwidthProperties();
    }

    @Test
    void recordThenReportShowsTheBytes() {
        BandwidthService svc = new BandwidthService(layout, props);
        svc.record("acme", 100, 2000);
        svc.record("acme", 50, 500);

        BandwidthReport report = svc.report("acme", 30);
        assertEquals(2500, report.out24h());
        assertEquals(150, report.in24h());
        assertEquals(2500, report.daily().get(29).bytesOut(), "today is the last daily point");
    }

    @Test
    void humanReadableSizes() {
        assertEquals("0 B", Bytes.human(0));
        assertEquals("512 B", Bytes.human(512));
        assertEquals("1.5 KB", Bytes.human(1536));
        assertEquals("2.0 MB", Bytes.human(2L * 1024 * 1024));
    }

    @Test
    void snapshotThenReloadRestoresTheCounters() {
        BandwidthService writer = new BandwidthService(layout, props);
        writer.record("acme", 10, 4096);
        writer.snapshotAll();
        assertTrue(Files.isRegularFile(layout.siteDir("acme").resolve("stats").resolve("bandwidth.csv")),
                "counters snapshotted to CSV");

        BandwidthService reloaded = new BandwidthService(layout, props);
        reloaded.reloadAll();
        assertEquals(4096, reloaded.report("acme", 30).out24h(), "reloaded from the CSV at startup");
    }

    @Test
    void aggregateRanksSitesByTraffic() {
        BandwidthService svc = new BandwidthService(layout, props);
        svc.record("small", 0, 100);
        svc.record("big", 0, 9000);

        List<SiteBandwidth> agg = svc.aggregate();
        assertEquals(2, agg.size());
        assertEquals("big", agg.get(0).siteId(), "busiest site first");
        assertEquals(9000, agg.get(0).bytesOut());
    }

    @Test
    void meteringOffRecordsNothing() {
        props.setEnabled(false);
        BandwidthService svc = new BandwidthService(layout, props);
        svc.record("acme", 100, 2000);
        assertEquals(0, svc.report("acme", 30).out24h());
    }

    @Test
    void overQuotaIsComputedFromTheOutboundThirtyDayTotal() {
        props.getQuota().setDefaultMonthlyBytes(1000);
        BandwidthService svc = new BandwidthService(layout, props);
        svc.record("acme", 0, 1500); // over the 1000-byte cap
        svc.record("blog", 0, 500);  // under
        svc.recomputeOverQuota();
        assertTrue(svc.isOverQuota("acme"));
        assertFalse(svc.isOverQuota("blog"));
    }

    @Test
    void aPerSiteQuotaOverridesTheDefault() {
        props.getQuota().setDefaultMonthlyBytes(0);      // default: unlimited
        props.getQuota().getPerSite().put("acme", 100L); // acme capped at 100 bytes
        BandwidthService svc = new BandwidthService(layout, props);
        svc.record("acme", 0, 200);
        svc.record("blog", 0, 1_000_000);               // huge but uncapped
        svc.recomputeOverQuota();
        assertTrue(svc.isOverQuota("acme"));
        assertFalse(svc.isOverQuota("blog"), "an uncapped site is never over quota");
    }

    @Test
    void withMeteringOffNoCapIsEnforced() {
        props.getQuota().setDefaultMonthlyBytes(100);
        BandwidthService svc = new BandwidthService(layout, props);
        svc.record("acme", 0, 200);
        svc.recomputeOverQuota();
        assertTrue(svc.isOverQuota("acme"));
        props.setEnabled(false); // as after a restart with runway.bandwidth.enabled=false over the saved totals
        assertFalse(svc.isOverQuota("acme"), "nothing is counted, so a cap could never lift: no metering, no cap");
    }

    @Test
    void withNoQuotaConfiguredNoSiteIsEverOver() {
        BandwidthService svc = new BandwidthService(layout, props);
        svc.record("acme", 0, 999_999_999);
        svc.recomputeOverQuota();
        assertFalse(svc.isOverQuota("acme"));
    }
}
