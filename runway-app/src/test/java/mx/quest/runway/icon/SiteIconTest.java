package mx.quest.runway.icon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The icon serving engine: store/read/remove, size cap, fixed-slot serving, and per-site scoping. */
class SiteIconTest {

    @TempDir
    Path external;
    private SiteIconService service;
    private SiteIconEndpoints endpoints;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        service = new SiteIconService(new SiteFileStore(layout));
        endpoints = new SiteIconEndpoints(service);
    }

    private static RouteRequest request(String siteId, String path) {
        SiteView site = new SiteView(siteId, siteId, Locale.ENGLISH, List.of(Locale.ENGLISH), "https://x.test");
        return new RouteRequest(site, Locale.ENGLISH, "GET", path, Map.of(), Map.of(), new byte[0]);
    }

    @Test
    void storeReadRemovePresentRoundTrip() throws IOException {
        assertFalse(service.present("acme", SiteIcon.SVG_FAVICON));
        service.store("acme", SiteIcon.SVG_FAVICON, "<svg/>".getBytes());
        assertTrue(service.present("acme", SiteIcon.SVG_FAVICON));
        assertArrayEquals("<svg/>".getBytes(), service.read("acme", SiteIcon.SVG_FAVICON).orElseThrow());
        assertTrue(service.remove("acme", SiteIcon.SVG_FAVICON));
        assertFalse(service.present("acme", SiteIcon.SVG_FAVICON));
    }

    @Test
    void anOversizeIconIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.store("acme", SiteIcon.ICON_512, new byte[(int) SiteIconService.MAX_ICON_BYTES + 1]));
    }

    @Test
    void theContributorDeclaresAGetRoutePerSlot() {
        assertEquals(SiteIcon.values().length, endpoints.routes().size());
        assertTrue(endpoints.routes().stream().allMatch(r -> r.method().equals("GET")));
        assertTrue(endpoints.routes().stream().anyMatch(r -> r.pathPattern().equals("/favicon.svg")));
    }

    @Test
    void aStoredIconIsServedWithItsSlotContentType() throws IOException {
        service.store("acme", SiteIcon.APPLE_TOUCH, new byte[] {1, 2, 3});
        RouteResponse response = endpoints.handle(request("acme", "/apple-touch-icon.png"));
        assertEquals(200, response.status());
        assertEquals("image/png", response.headers().get("Content-Type"));
        assertArrayEquals(new byte[] {1, 2, 3}, response.body());
    }

    @Test
    void anAbsentIconIsA404() {
        assertEquals(404, endpoints.handle(request("acme", "/favicon.ico")).status());
    }

    @Test
    void anIconIsScopedToItsOwnSite() throws IOException {
        service.store("other", SiteIcon.SVG_FAVICON, "OTHER".getBytes());
        assertEquals(404, endpoints.handle(request("acme", "/favicon.svg")).status(), "acme never sees other's icon");
    }

    @Test
    void slotsResolveByKeyAndUrl() {
        assertEquals(SiteIcon.ICON_192, SiteIcon.fromKey("icon-192").orElseThrow());
        assertEquals(SiteIcon.ICON_192, SiteIcon.fromUrl("/icon-192.png").orElseThrow());
        assertTrue(SiteIcon.fromKey("../etc/passwd").isEmpty(), "an unknown slot key never resolves");
    }

    @Test
    void theSvgIconIsSandboxedAndTheRasterIconsAreNot() throws IOException {
        service.store("acme", SiteIcon.SVG_FAVICON, "<svg/>".getBytes());
        RouteResponse svg = endpoints.handle(request("acme", "/favicon.svg"));
        assertEquals(200, svg.status());
        assertEquals("image/svg+xml", svg.headers().get("Content-Type"));
        assertEquals("sandbox", svg.headers().get("Content-Security-Policy"));
        service.store("acme", SiteIcon.APPLE_TOUCH, new byte[] {1});
        assertFalse(endpoints.handle(request("acme", "/apple-touch-icon.png")).headers()
                .containsKey("Content-Security-Policy"));
    }
}
