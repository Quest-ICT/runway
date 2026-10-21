package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import mx.quest.runway.log.AuditEntry;
import mx.quest.runway.log.AuditPage;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ExtendedModelMap;

/**
 * The audit viewer's presentation contract: filter parsing is forgiving and bounded, labels localize and fall
 * back to the raw key, paging links keep the filters (URL-encoded), and the CSV is RFC 4180 + formula-safe.
 */
class AuditViewTest {

    private static ResourceBundleMessageSource operatorMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/operator-messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false); // the workstation's es_MX must not leak into the EN assertions
        return source;
    }

    private static AuditEntry entry(String actor, String action, String detail) {
        return new AuditEntry(OffsetDateTime.parse("2026-10-17T08:10:03.123-06:00"), true, actor, action, detail);
    }

    // --- filter -----------------------------------------------------------------------------------

    @Test
    void aMalformedDateIsDroppedNotRejected() {
        AuditView.Filter f = AuditView.filter("2026-02-30", "yesterday", null, null, null);
        assertEquals("", f.from());
        assertEquals("", f.to());
        assertNull(f.query().from());
        assertNull(f.query().to());
    }

    @Test
    void anInvertedRangeIsReadTheObviousWay() {
        AuditView.Filter f = AuditView.filter("2026-10-17", "2026-10-01", null, null, 0);
        assertEquals("2026-10-01", f.from());
        assertEquals("2026-10-17", f.to());
    }

    @Test
    void thePageIsClampedAndTextFiltersAreStrippedAndClipped() {
        assertEquals(0, AuditView.filter(null, null, null, null, -5).page());
        assertEquals(10_000, AuditView.filter(null, null, null, null, Integer.MAX_VALUE).page());
        AuditView.Filter f = AuditView.filter(null, null, "  bob  ", "x".repeat(500), 0);
        assertEquals("bob", f.actor());
        assertEquals(100, f.action().length());
    }

    // --- labels -----------------------------------------------------------------------------------

    @Test
    void aKnownActionIsLabelledInTheViewersLanguage() {
        ResourceBundleMessageSource m = operatorMessages();
        assertEquals("Site created", AuditView.label("site.created", m, Locale.ENGLISH));
        assertEquals("Sitio creado", AuditView.label("site.created", m, Locale.forLanguageTag("es-MX")));
        assertEquals("Role assigned to a user", AuditView.label("permissions.role-assigned", m, Locale.ENGLISH));
    }

    @Test
    void anUnknownActionFallsBackToItsRawKey() {
        assertEquals("blog.post-imported", AuditView.label("blog.post-imported", operatorMessages(), Locale.ENGLISH));
        assertEquals("", AuditView.label("", operatorMessages(), Locale.ENGLISH));
    }

    @Test
    void aLegacySuffixedKeyIsLabelledByItsBase() {
        assertEquals("Site superadmin assigned to a site",
                AuditView.label("master.assigned:acme", operatorMessages(), Locale.ENGLISH));
    }

    @Test
    void everyActionLabelExistsInBothLanguagesAndCoversTheRecordedKeys() throws IOException {
        Set<String> en = actionKeys("i18n/operator-messages.properties");
        Set<String> es = actionKeys("i18n/operator-messages_es.properties");
        assertEquals(en, es, "EN and ES label the same actions");
        for (String recorded : List.of("login.success", "login.failure", "logout", "recovery.requested",
                "recovery.completed", "permissions.role-assigned", "permissions.role-revoked",
                "permissions.permission-granted", "permissions.permission-revoked",
                "permissions.role-permission-granted", "permissions.role-permission-revoked",
                "permissions.role-created", "permissions.role-deleted", "permissions.role-inherit-added",
                "permissions.role-inherit-removed", "page.saved", "page.deleted", "page.published",
                "page.unpublished", "user.created", "user.password-reset", "user.deleted",
                "plugin.settings-changed", "logs.settings-changed", "site-config.changed", "master.assigned",
                "master.unassigned", "site.created", "site.hosts-updated", "backup.saved",
                "platform.kernel-settings-changed", "platform.kernel-setting-adopted")) {
            assertTrue(en.contains(recorded), "a label for " + recorded);
        }
    }

    private static Set<String> actionKeys(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = AuditViewTest.class.getClassLoader().getResourceAsStream(resource)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p.stringPropertyNames().stream().filter(k -> k.startsWith("op.audit.action."))
                .map(k -> k.substring("op.audit.action.".length())).collect(Collectors.toCollection(TreeSet::new));
    }

    // --- model ------------------------------------------------------------------------------------

    @Test
    void pagingLinksKeepTheFiltersEncoded() {
        ExtendedModelMap model = new ExtendedModelMap();
        AuditView.Filter f = AuditView.filter("2026-10-01", null, "a&b c", "page.", 1);
        AuditView.populate(model, new AuditPage(List.of(entry("x", "y", "z")), 100, true, false, 5), f,
                "/__app/logs", "/__app/logs/export.csv", "op.applog.audit", "runway.app.audit", operatorMessages(),
                Locale.ENGLISH);
        String next = (String) model.getAttribute("auditNextUrl");
        String prev = (String) model.getAttribute("auditPrevUrl");
        String csv = (String) model.getAttribute("auditCsvUrl");
        assertTrue(next.startsWith("/__app/logs?") && next.contains("page=2"), next);
        assertTrue(next.contains("actor=a%26b%20c") && next.contains("from=2026-10-01") && next.contains("action=page."),
                next);
        assertFalse(prev.contains("page="), "page 0 needs no page parameter: " + prev);
        assertTrue(csv.startsWith("/__app/logs/export.csv?") && !csv.contains("page="), csv);
        assertEquals(2, model.getAttribute("auditPageNumber"));
    }

    @Test
    void theFirstAndLastPagesHaveNoPrevOrNextLink() {
        ExtendedModelMap model = new ExtendedModelMap();
        AuditView.populate(model, new AuditPage(List.of(), 0, false, true, 7), AuditView.filter(null, null, null,
                null, 0), "/x", "/x.csv", "op.applog.audit", "k", operatorMessages(), Locale.ENGLISH);
        assertNull(model.getAttribute("auditPrevUrl"));
        assertNull(model.getAttribute("auditNextUrl"));
        assertEquals(true, model.getAttribute("auditTruncated"));
        assertEquals(7L, model.getAttribute("auditScanned"));
    }

    // --- CSV --------------------------------------------------------------------------------------

    @Test
    void formulaLeadingCellsAreNeutralized() {
        for (String evil : List.of("=HYPERLINK(\"x\")", "+1", "-2+3", "@SUM(A1)", "\tx", "\rx")) {
            assertTrue(AuditView.csvSafe(evil).startsWith("'"), evil);
        }
        assertEquals("plain", AuditView.csvSafe("plain"));
        assertEquals("a=b", AuditView.csvSafe("a=b"), "only a LEADING trigger is dangerous");
        assertEquals("", AuditView.csvSafe(null));
    }

    @Test
    void theCsvIsQuotedBomPrefixedAndCarriesRawKeyAndLabel() {
        AuditPage page = new AuditPage(List.of(entry("=cmd|' /C calc'!A0", "site.created", "say \"hi\", ok")),
                0, false, false, 1);
        ResponseEntity<byte[]> response = AuditView.csv(page, "x.csv", operatorMessages(), Locale.ENGLISH);
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("﻿\"time\",\"actor\",\"action\",\"label\",\"detail\"\r\n"), body);
        assertTrue(body.contains("\"2026-10-17T08:10:03.123-06:00\""), body);
        assertTrue(body.contains("\"'=cmd|' /C calc'!A0\""), "the formula is defused: " + body);
        assertTrue(body.contains("\"site.created\",\"Site created\""), body);
        assertTrue(body.contains("\"say \"\"hi\"\", ok\""), "quotes doubled, comma kept inside: " + body);
        assertEquals("text/csv;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNull(response.getHeaders().getFirst("X-Runway-Audit-Incomplete"));
    }

    @Test
    void anIncompleteExportSaysSo() {
        ResponseEntity<byte[]> capped = AuditView.csv(new AuditPage(List.of(), 0, true, false, 1), "x.csv",
                operatorMessages(), Locale.ENGLISH);
        assertEquals("true", capped.getHeaders().getFirst("X-Runway-Audit-Incomplete"));
        ResponseEntity<byte[]> truncated = AuditView.csv(new AuditPage(List.of(), 0, false, true, 1), "x.csv",
                operatorMessages(), Locale.ENGLISH);
        assertEquals("true", truncated.getHeaders().getFirst("X-Runway-Audit-Incomplete"));
    }
}
