package mx.quest.runway.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** /manifest.webmanifest: 404 when nothing, else JSON with configured fields + derived icons. */
class WebManifestEndpointTest {

    @TempDir
    Path external;
    private SiteIconService icons;
    private SiteManifestService manifests;
    private WebManifestEndpoint endpoint;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        icons = new SiteIconService(new SiteFileStore(layout));
        manifests = new SiteManifestService(layout, new YamlStore());
        endpoint = new WebManifestEndpoint(manifests, icons);
    }

    private static RouteRequest request(String siteId) {
        SiteView site = new SiteView(siteId, siteId, Locale.ENGLISH, List.of(Locale.ENGLISH), "https://x.test");
        return new RouteRequest(site, Locale.ENGLISH, "GET", "/manifest.webmanifest", Map.of(), Map.of(), new byte[0]);
    }

    @Test
    void nothingConfiguredIsA404() {
        assertEquals(404, endpoint.handle(request("acme")).status());
    }

    @Test
    void configuredFieldsAndDerivedIconsAreServedAsJson() throws IOException {
        manifests.save("acme", new SiteManifest("Acme", "Acme", null, "#0d6efd", null, "standalone", "en"));
        icons.store("acme", SiteIcon.ICON_192, new byte[] {1});
        icons.store("acme", SiteIcon.ICON_512, new byte[] {2});
        icons.store("acme", SiteIcon.FAVICON_ICO, new byte[] {3}); // not a manifest icon

        RouteResponse response = endpoint.handle(request("acme"));
        assertEquals(200, response.status());
        assertTrue(response.headers().get("Content-Type").startsWith("application/manifest+json"));
        String json = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"name\":\"Acme\""), json);
        assertTrue(json.contains("\"theme_color\":\"#0d6efd\""), json);
        assertTrue(json.contains("\"src\":\"/icon-192.png\""), json);
        assertTrue(json.contains("\"sizes\":\"512x512\""), json);
        assertFalse(json.contains("favicon.ico"), "favicon.ico is not a manifest icon");
    }

    @Test
    void iconsAloneAreEnoughToServeAManifest() throws IOException {
        icons.store("acme", SiteIcon.ICON_192, new byte[] {1});
        assertEquals(200, endpoint.handle(request("acme")).status(), "a manifest-eligible icon is enough");
    }
}
