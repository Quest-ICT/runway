package mx.quest.runway.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The async log capture engine: write, anonymize, audit, injection-safety, tail, retention prune. */
class SiteLogServiceTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private SiteLogConfigService configs;
    private LogProperties props;

    @BeforeEach
    void setUp() {
        StorageProperties sp = new StorageProperties();
        sp.setExternal(external);
        layout = StorageLayout.from(sp);
        configs = new SiteLogConfigService(layout, new YamlStore());
        props = new LogProperties();
    }

    /** Offers events, then flushes deterministically by stopping the daemon (which drains what remains). */
    private SiteLogService started() {
        SiteLogService svc = new SiteLogService(layout, configs, props);
        svc.start();
        return svc;
    }

    @Test
    void accessIsCapturedAnonymizedByDefaultAndTailable() {
        SiteLogService svc = started();
        svc.access("acme", "1.2.3.4", "GET", "/about", 200);
        svc.stop();

        List<String> lines = svc.tail("acme", LogKind.ACCESS, 10);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("1.2.3.0"), lines.get(0)); // last octet masked
        assertFalse(lines.get(0).contains("1.2.3.4"), "the full IP is never persisted when anonymizing");
        assertTrue(lines.get(0).contains("GET\t/about\t200"), lines.get(0));
    }

    @Test
    void anonymizationCanBeTurnedOff() throws IOException {
        configs.save("acme", new SiteLogConfig(false, null));
        SiteLogService svc = started();
        svc.access("acme", "1.2.3.4", "GET", "/x", 200);
        svc.stop();
        assertTrue(svc.tail("acme", LogKind.ACCESS, 10).get(0).contains("1.2.3.4"));
    }

    @Test
    void auditIsCapturedInItsOwnLogSeparateFromAccess() {
        SiteLogService svc = started();
        svc.audit("acme", "admin", "login.success", "");
        svc.stop();
        assertTrue(svc.tail("acme", LogKind.AUDIT, 10).get(0).contains("admin\tlogin.success"));
        assertTrue(svc.tail("acme", LogKind.ACCESS, 10).isEmpty(), "audit does not leak into the access log");
    }

    @Test
    void crlfInAFieldCannotInjectANewLogLine() {
        SiteLogService svc = started();
        svc.access("acme", "1.2.3.4", "GET", "/x\r\nFAKE 500 injected", 200);
        svc.stop();
        assertEquals(1, svc.tail("acme", LogKind.ACCESS, 10).size(), "the CRLF was stripped — still one line");
    }

    @Test
    void logsAreScopedToTheirSite() {
        SiteLogService svc = started();
        svc.access("other", "9.9.9.9", "GET", "/x", 200);
        svc.stop();
        assertTrue(svc.tail("acme", LogKind.ACCESS, 10).isEmpty(), "acme never sees other's log");
    }

    @Test
    void auditLinesCarryAnOffsetWhileAccessLinesKeepTheirShape() {
        SiteLogService svc = started();
        svc.audit("acme", "admin", "page.published", "home");
        svc.access("acme", "1.2.3.4", "GET", "/", 200);
        svc.stop();
        String audit = svc.tail("acme", LogKind.AUDIT, 1).get(0);
        String access = svc.tail("acme", LogKind.ACCESS, 1).get(0);
        assertTrue(audit.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})\\t.*"), audit);
        assertTrue(access.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\t.*"),
                "the access log is unchanged (operators may parse it): " + access);
    }

    @Test
    void queryAuditReadsOnlyThatSitesJournal() {
        SiteLogService svc = started();
        svc.audit("acme", "admin", "page.saved", "home");
        svc.audit("other", "intruder", "page.deleted", "secret");
        svc.stop();
        AuditPage acme = svc.queryAudit("acme", AuditQuery.ALL, 0, 10);
        assertEquals(1, acme.rows().size());
        assertEquals("page.saved", acme.rows().get(0).action());
        assertTrue(acme.rows().get(0).offsetKnown());
        assertEquals("intruder", svc.queryAudit("other", AuditQuery.ALL, 0, 10).rows().get(0).actor());
    }

    @Test
    void queryAuditOfAnUnsafeSiteIdReadsNothing() {
        SiteLogService svc = started();
        svc.audit("acme", "admin", "page.saved", "home");
        svc.stop();
        assertTrue(svc.queryAudit("../acme", AuditQuery.ALL, 0, 10).rows().isEmpty());
        assertTrue(svc.queryAudit("acme/../acme", AuditQuery.ALL, 0, 10).rows().isEmpty());
    }

    @Test
    void retentionPrunesDayFilesOlderThanTheClampedWindow() throws IOException {
        Path dir = Files.createDirectories(layout.siteDir("acme").resolve("logs"));
        Path old = Files.writeString(dir.resolve("access-2020-01-01.log"), "old\n");
        LocalDate today = LocalDate.now();
        Path recent = Files.writeString(dir.resolve("access-" + today.minusDays(1) + ".log"), "recent\n");
        configs.save("acme", new SiteLogConfig(null, 5)); // keep 5 days

        SiteLogService svc = new SiteLogService(layout, configs, props);
        svc.pruneAll(today);

        assertFalse(Files.exists(old), "a file older than the retention window is pruned");
        assertTrue(Files.exists(recent), "a recent file is kept");
    }

    @Test
    void maskIpHandlesIpv4AndIpv6() {
        assertEquals("203.0.113.0", SiteLogService.maskIp("203.0.113.45"));
        assertEquals("2001:db8:1::", SiteLogService.maskIp("2001:db8:1:2:3:4:5:6"));
    }

    @Test
    void tailOfAnOversizedDayFileReadsOnlyTheEndAndReturnsTheLastLines() throws IOException {
        // A day-file well over the 512 KiB tail window must not be pulled whole into the heap.
        Path dir = Files.createDirectories(layout.siteDir("acme").resolve("logs"));
        LocalDate today = LocalDate.now();
        Path file = dir.resolve("access-" + today + ".log");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20_000; i++) { // ~20k lines of ~40 bytes ≈ 800 KB > 512 KiB window
            big.append("2026-07-02 00:00:00\t10.0.0.1\tGET\t/page-").append(i).append("\t200\n");
        }
        Files.writeString(file, big.toString());

        SiteLogService svc = new SiteLogService(layout, configs, props);
        List<String> lines = svc.tail("acme", LogKind.ACCESS, 3);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).endsWith("/page-19999\t200"), lines.get(2)); // the newest line survives
    }
}
