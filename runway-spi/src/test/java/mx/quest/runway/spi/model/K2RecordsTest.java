package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The records SPI 0.5.0 adds or extends: frozen, null-normalized, and the old constructors keep their meaning. */
class K2RecordsTest {

    private static final SiteView SITE = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(), null);

    // --- RouteRequest ----------------------------------------------------------------------------------------

    @Test
    void theOldSevenArgumentFormHasNoFormNoClientIpAndAnEmptyOrigin() {
        RouteRequest old = new RouteRequest(SITE, Locale.ENGLISH, "GET", "/x", null, null, null);
        assertEquals(Map.of(), old.form());
        assertNull(old.clientIp());
        assertEquals("", old.origin());
    }

    @Test
    void headerNamesAreLowercasedAndLookupsIgnoreCase() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "UA");
        headers.put("user-agent", "second spelling loses");
        headers.put("X-A", "1");
        headers.put(null, "dropped");
        headers.put("X-Null", null);
        RouteRequest req = new RouteRequest(SITE, Locale.ENGLISH, "GET", "/x", null, headers, null);
        assertEquals(List.of("user-agent", "x-a"), new ArrayList<>(req.headers().keySet()));
        assertEquals("UA", req.headers().get("USER-AGENT"));
        assertTrue(req.headers().containsKey("x-A"));
        assertThrows(UnsupportedOperationException.class, () -> req.headers().put("a", "b"));
    }

    @Test
    void formIsDeepCopiedAndParamPrefersTheQuery() {
        Map<String, List<String>> form = new HashMap<>();
        List<String> names = new ArrayList<>(List.of("Ana"));
        form.put("name", names);
        form.put("both", List.of("form"));
        form.put("none", List.of());
        RouteRequest req = new RouteRequest(SITE, Locale.ENGLISH, "POST", "/x", Map.of("both", List.of("query")),
                null, null, form, " ", null);
        names.add("mutated");
        assertEquals(List.of("Ana"), req.form().get("name"), "deep-copied");
        assertThrows(UnsupportedOperationException.class, () -> req.form().get("name").add("x"));
        assertEquals(Optional.of("query"), req.param("both"));
        assertEquals(Optional.of("Ana"), req.param("name"));
        assertEquals(Optional.empty(), req.param("none"));
        assertEquals(Optional.empty(), req.param("absent"));
        assertNull(req.clientIp(), "a blank client IP is unknown");
        assertEquals("", req.origin());
        assertThrows(NullPointerException.class, () -> req.param(null));
    }

    // --- Route -----------------------------------------------------------------------------------------------

    @Test
    void routesAreNotAnonymousUnlessDeclared() {
        assertFalse(new Route("POST", "/x", Realm.TENANT, null).anonymous());
        assertFalse(Route.get("/x", Realm.TENANT).anonymous());
        Route anonymous = Route.anonymousPost("/contact/send", null);
        assertTrue(anonymous.anonymous());
        assertEquals("POST", anonymous.method());
        assertEquals(Realm.TENANT, anonymous.realm());
        assertEquals(SurfacePosture.safeDefault(), anonymous.posture());
    }

    // --- FileEntry, StarterPage, InstallResult ---------------------------------------------------------------

    @Test
    void fileEntryNormalizesSizeAndTime() {
        FileEntry dir = new FileEntry("d", true, 4096, null);
        assertEquals(0, dir.size(), "a folder has no size");
        assertEquals(Instant.EPOCH, dir.lastModified());
        assertEquals(0, new FileEntry("f", false, -5, Instant.EPOCH).size());
        assertThrows(NullPointerException.class, () -> new FileEntry(null, false, 1, null));
    }

    @Test
    void starterPageFreezesTitlesAndNormalizesBlankLogic() {
        Map<String, String> titles = new HashMap<>();
        titles.put("en", "Contact");
        titles.put(null, "x");
        titles.put("es", null);
        StarterPage page = new StarterPage("contact", "/contact", titles, "<p/>", "  ", true);
        assertEquals(Map.of("en", "Contact"), page.titles());
        assertNull(page.logic());
        assertThrows(UnsupportedOperationException.class, () -> page.titles().put("fr", "x"));
        assertEquals(Map.of(), new StarterPage("s", "/s", null, "", null, false).titles());
        assertThrows(NullPointerException.class, () -> new StarterPage(null, "/s", null, "", null, false));
        assertThrows(NullPointerException.class, () -> new StarterPage("s", "/s", null, null, null, false));
    }

    @Test
    void installResultFreezesItsFindings() {
        List<Finding> findings = new ArrayList<>(List.of(Finding.error("x", "y")));
        InstallResult result = new InstallResult(InstallResult.Outcome.DRAFT_BLOCKED, "/c", findings);
        findings.clear();
        assertEquals(1, result.blocking().size());
        assertEquals(List.of(), new InstallResult(InstallResult.Outcome.PUBLISHED, "/c", null).blocking());
        assertThrows(NullPointerException.class, () -> new InstallResult(null, "/c", null));
    }
}
