package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FactoriesTest {

    // --- RenderFragment ---

    @Test
    void renderFragmentEmptyVariantsAreEmptyAndShared() {
        assertTrue(RenderFragment.empty().isEmpty());
        assertTrue(RenderFragment.of(null).isEmpty());
        assertTrue(RenderFragment.of("").isEmpty());
        assertSame(RenderFragment.empty(), RenderFragment.of(null));
        assertSame(RenderFragment.empty(), RenderFragment.of(""));
    }

    @Test
    void renderFragmentOfHoldsMarkup() {
        RenderFragment f = RenderFragment.of("<div>");
        assertEquals("<div>", f.html());
        assertFalse(f.isEmpty());
    }

    @Test
    void renderFragmentNullHtmlNormalizes() {
        assertEquals("", new RenderFragment(null).html());
    }

    // --- RouteResponse factories ---

    @Test
    void routeResponseOk() {
        RouteResponse r = RouteResponse.ok(new byte[] {1});
        assertEquals(200, r.status());
        assertEquals(1, r.body().length);
    }

    @Test
    void routeResponseNotFound() {
        RouteResponse r = RouteResponse.notFound();
        assertEquals(404, r.status());
        assertEquals(0, r.body().length);
    }

    @Test
    void routeResponseText() {
        RouteResponse r = RouteResponse.text(200, "hi");
        assertEquals("text/plain; charset=utf-8", r.headers().get("Content-Type"));
        assertEquals("hi", new String(r.body(), StandardCharsets.UTF_8));
    }

    @Test
    void routeResponseTextNullBody() {
        assertEquals(0, RouteResponse.text(204, null).body().length);
    }

    // --- SurfacePosture ---

    @Test
    void surfacePostureSafeDefaultIsAllFalse() {
        SurfacePosture p = SurfacePosture.safeDefault();
        assertFalse(p.indexable());
        assertFalse(p.inSitemap());
        assertFalse(p.cacheable());
    }

    @Test
    void surfacePostureDiscoverableIndexesButDoesNotCache() {
        SurfacePosture p = SurfacePosture.discoverable();
        assertTrue(p.indexable());
        assertTrue(p.inSitemap());
        assertFalse(p.cacheable());
    }

    // --- Finding ---

    @Test
    void findingSeverityFactories() {
        assertEquals(Severity.INFO, Finding.info("c", "m").severity());
        assertEquals(Severity.WARNING, Finding.warning("c", "m").severity());
        assertEquals(Severity.ERROR, Finding.error("c", "m").severity());
    }

    @Test
    void findingNullMessageNormalizes() {
        assertEquals("", new Finding(Severity.INFO, "c", null).message());
    }

    @Test
    void findingRequiresSeverityAndCode() {
        assertThrows(NullPointerException.class, () -> new Finding(null, "c", "m"));
        assertThrows(NullPointerException.class, () -> new Finding(Severity.INFO, null, "m"));
    }

    // --- enums ---

    @Test
    void realmHasBothDomains() {
        assertEquals(2, Realm.values().length);
        assertNotNull(Realm.valueOf("APPLICATION"));
        assertNotNull(Realm.valueOf("TENANT"));
    }

    @Test
    void severityHasThreeLevels() {
        assertEquals(3, Severity.values().length);
    }
}
