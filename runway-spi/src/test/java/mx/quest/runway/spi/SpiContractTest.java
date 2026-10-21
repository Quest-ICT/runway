package mx.quest.runway.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.Severity;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import org.junit.jupiter.api.Test;

/**
 * Proves each Group A SPI is implementable as a pure, dependency-free unit — no Spring, no
 * real I/O — exactly as the design intends. Doubles as worked usage of each contract.
 */
class SpiContractTest {

    private static final Locale EN = Locale.ENGLISH;

    private static SiteView site() {
        return new SiteView("s1", "Site", EN, List.of(EN), "https://example.test");
    }

    private static PageMeta page() {
        return new PageMeta("home", "Home", "A page", List.of(EN), EN, true, false);
    }

    @Test
    void headContributorIsAPureFunction() {
        HeadContributor c = new HeadContributor() {
            @Override
            public String key() {
                return "test.meta";
            }

            @Override
            public String render(HeadContext ctx) {
                return "<meta name=\"slug\" content=\"" + ctx.page().slug() + "\">";
            }
        };
        assertEquals("test.meta", c.key());
        assertTrue(c.capabilities().isEmpty(), "default capabilities() is none()");
        HeadContext ctx = new HeadContext(site(), page(), EN, null, null, null, false, false);
        assertEquals("<meta name=\"slug\" content=\"home\">", c.render(ctx));
    }

    @Test
    void contentWidgetRendersAndSoftDegrades() {
        ContentWidget present = new ContentWidget() {
            @Override
            public String key() {
                return "test.box";
            }

            @Override
            public RenderFragment render(WidgetView view) {
                return RenderFragment.of("<div>" + view.instanceId() + "</div>");
            }

            @Override
            public Capabilities capabilities() {
                return Capabilities.of("widget.configurable");
            }
        };
        ContentWidget absent = new ContentWidget() {
            @Override
            public String key() {
                return "test.absent";
            }

            @Override
            public RenderFragment render(WidgetView view) {
                return RenderFragment.empty();
            }
        };
        WidgetView view = new WidgetView(site(), page(), EN, "hero", false, Map.of());
        assertEquals("<div>hero</div>", present.render(view).html());
        assertTrue(present.capabilities().has("widget.configurable"));
        assertTrue(absent.render(view).isEmpty(), "soft-degrade yields an empty fragment");
    }

    @Test
    void pageQualityCheckReportsAdvisoryFindings() {
        PageQualityCheck check = new PageQualityCheck() {
            @Override
            public String key() {
                return "test.title-length";
            }

            @Override
            public List<Finding> inspect(PageView page) {
                if (page.page().title().length() > 60) {
                    return List.of(Finding.warning("title.too-long", "Title exceeds 60 characters"));
                }
                return List.of();
            }
        };
        assertTrue(check.inspect(new PageView(site(), page(), EN, null, "<html>")).isEmpty());

        PageMeta longTitle = new PageMeta("p", "x".repeat(61), "", List.of(EN), EN, true, false);
        List<Finding> findings = check.inspect(new PageView(site(), longTitle, EN, null, "<html>"));
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void endpointContributorDeclaresRoutesAndHandles() {
        EndpointContributor endpoint = new EndpointContributor() {
            @Override
            public String key() {
                return "test.robots";
            }

            @Override
            public List<Route> routes() {
                return List.of(Route.get("/robots.txt", Realm.TENANT));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                // A handler can build absolute links from the full SiteView it receives.
                return RouteResponse.text(200, "Sitemap: " + request.site().baseUrl() + "/sitemap.xml");
            }
        };
        assertEquals(1, endpoint.routes().size());
        assertEquals("/robots.txt", endpoint.routes().get(0).pathPattern());
        assertFalse(endpoint.routes().get(0).posture().indexable(), "fail-safe posture by default");

        RouteRequest request = new RouteRequest(site(), EN, "GET", "/robots.txt", null, null, null);
        RouteResponse response = endpoint.handle(request);
        assertEquals(200, response.status());
        assertEquals("Sitemap: https://example.test/sitemap.xml", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void backupParticipantRoundTripsAndImportIsIdempotent() throws IOException {
        BackupParticipant participant = new BackupParticipant() {
            @Override
            public String key() {
                return "test.notes";
            }

            @Override
            public void export(BackupWriter out, SiteRef siteRef) throws IOException {
                try (OutputStream os = out.put("note.txt")) {
                    os.write(("note for " + siteRef.id()).getBytes(StandardCharsets.UTF_8));
                }
            }

            @Override
            public void importInto(BackupReader in, SiteRef siteRef) throws IOException {
                if (in.has("note.txt")) {
                    try (InputStream is = in.open("note.txt")) {
                        is.readAllBytes();
                    }
                }
            }
        };

        InMemoryBackup store = new InMemoryBackup();
        participant.export(store, new SiteRef("s1"));
        assertTrue(store.has("note.txt"));
        assertEquals("note for s1", new String(store.bytes("note.txt"), StandardCharsets.UTF_8));

        // Importing twice must not throw and must not change state (idempotent restore).
        participant.importInto(store, new SiteRef("s1"));
        participant.importInto(store, new SiteRef("s1"));
        assertEquals("note for s1", new String(store.bytes("note.txt"), StandardCharsets.UTF_8));
    }

    /** A trivial in-memory {@link BackupWriter}+{@link BackupReader}, proving the contract needs no host. */
    private static final class InMemoryBackup implements BackupWriter, BackupReader {

        private final Map<String, byte[]> entries = new HashMap<>();

        @Override
        public OutputStream put(String entry) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    entries.put(entry, toByteArray());
                }
            };
        }

        @Override
        public boolean has(String entry) {
            return entries.containsKey(entry);
        }

        @Override
        public InputStream open(String entry) throws IOException {
            byte[] bytes = entries.get(entry);
            if (bytes == null) {
                throw new IOException("no such entry: " + entry);
            }
            return new ByteArrayInputStream(bytes);
        }

        byte[] bytes(String entry) {
            return entries.get(entry);
        }
    }
}
