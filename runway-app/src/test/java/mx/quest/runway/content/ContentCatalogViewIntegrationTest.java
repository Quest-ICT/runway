package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.page.ContentUrls;
import mx.quest.runway.page.LocaleUrls;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link ContentCatalogView} over the real kernel (contract tests): parity of every URL with the host's
 * one URL authority ({@link ContentUrls}), the fallback, {@code pageAt}, {@code servablePath}, {@code refresh},
 * the id rules, and the last-seen URL index that outlives eviction.
 */
@SpringBootTest
class ContentCatalogViewIntegrationTest {

    private static final String SITE = "views";
    private static final Locale ES = Locale.forLanguageTag("es");
    private static Path templatesDir;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-k3-catalog-view");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site(SITE, "Views", List.of("views.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, ES), "https://views.test", SiteStatus.ACTIVE));
        templatesDir = Files.createDirectories(layout.siteDir(SITE).resolve("templates"));
        Files.writeString(templatesDir.resolve("_master.html"), "<html><head></head><body></body></html>");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Catalog-View-Secret-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root",
                "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private ContentCatalogView view;
    @Autowired
    private ContentCatalog catalog;
    @Autowired
    private PageWriteService writer;
    @Autowired
    private PageStore pages;
    @Autowired
    private SiteStore sites;
    @Autowired
    private ApplicationEventPublisher events;

    private SiteView site;

    @BeforeEach
    void pages() {
        site = SiteViews.toView(sites.find(SITE).orElseThrow());
        writer.save(SITE, new Page("about", "/about", Map.of("es", "/acerca"), List.of(Locale.ENGLISH, ES),
                Map.of("en", "About", "es", "Acerca"), Map.of(), "", "_master", false, false, null, null), "<p/>", null);
        writer.save(SITE, new Page("solo", "/solo", Map.of(), List.of(ES), Map.of("es", "Solo"), Map.of(), "",
                "_master", false), "<p>texto solo</p>", null);
        writer.save(SITE, new Page("hidden", "/hidden", Map.of(), List.of(), Map.of("en", "H"), Map.of(), "",
                "_master", true), "<p/>", null);
        writer.save(SITE, new Page("canon", "/canon", Map.of(), List.of(), Map.of("en", "C"), Map.of(), "",
                "_master", false, false, "https://elsewhere.test/original", null), "<p/>", null);
    }

    private CatalogEntry entry(String slug) {
        return view.entries(SITE).stream().filter(e -> e.ref().id().equals(slug)).findFirst().orElse(null);
    }

    @Test
    void everyEntryAgreesWithTheHostsUrlAuthority() {
        List<CatalogEntry> entries = view.entries(SITE);
        assertFalse(entries.isEmpty());
        for (CatalogEntry entry : entries) {
            Page page = pages.find(SITE, entry.ref().id()).orElseThrow();
            List<String> expected = ContentUrls.alternates(page, site, "").stream().map(Alternate::url).toList();
            assertEquals(expected, entry.urls().stream().map(LocalizedUrl::path).toList(),
                    "the <head> alternates and the catalog list the same addresses for " + entry.compositeId());
            assertEquals(ContentUrls.xDefault(page, site, ""), entry.xDefaultPath());
            for (LocalizedUrl url : entry.urls()) {
                assertEquals(ContentUrls.canonicalUrl(page, url.locale(), site.defaultLocale(), ""),
                        url.canonical(), "the canonical agrees with the <head>'s too");
                assertEquals(site.baseUrl() + url.path(), url.absolute(site.baseUrl()));
            }
            assertTrue(entry.isPage());
            assertEquals("pages:" + page.slug(), entry.compositeId());
        }
    }

    @Test
    void localizedAddressesAndTheCanonicalOverride() {
        CatalogEntry about = entry("about");
        assertEquals(List.of(new LocalizedUrl(Locale.ENGLISH, "/about", null), new LocalizedUrl(ES, "/es/acerca",
                null)), about.urls());
        assertEquals("/es/acerca", about.pathFor(ES));
        CatalogEntry canon = entry("canon");
        assertEquals("https://elsewhere.test/original", canon.urls().get(0).canonical(), "points elsewhere");
        assertEquals("/canon", canon.urls().get(0).path(), "but the routing URL is the real one");
    }

    @Test
    void anItemMissingFromTheDefaultLocaleLinksItsPrimaryLocalesRealUrl() {
        // Review n6: the old link (urlFor(item, siteDefault, siteDefault)) gave the unprefixed path, which 404s.
        CatalogEntry solo = entry("solo");
        assertEquals("/solo", LocaleUrls.urlFor(new RoutableContent(solo.sourceKey(), solo.ref()), Locale.ENGLISH,
                Locale.ENGLISH), "the pre-0.6.0 behaviour this replaces");
        assertEquals("/es/solo", solo.pathFor(Locale.ENGLISH), "the intended change");
        assertEquals(List.of(new LocalizedUrl(ES, "/es/solo", null)), solo.urls());
    }

    @Test
    void draftsAreNotListed() {
        assertEquals(null, entry("hidden"));
    }

    @Test
    void pageAtAnswersThePublishedRealPageOwningAPath() {
        assertEquals(Optional.of("about"), view.pageAt(SITE, "/about", Locale.ENGLISH));
        assertEquals(Optional.of("about"), view.pageAt(SITE, "/acerca", ES));
        assertEquals(Optional.empty(), view.pageAt(SITE, "/acerca", Locale.ENGLISH), "per-locale URL space");
        assertEquals(Optional.empty(), view.pageAt(SITE, "/hidden", Locale.ENGLISH), "a draft owns no path");
        assertEquals(Optional.empty(), view.pageAt(SITE, "/nothing", Locale.ENGLISH));
        assertEquals(Optional.empty(), view.pageAt("nosuchsite", "/about", Locale.ENGLISH));
    }

    @Test
    void servablePathIsTheHostsRoutingRuleWithoutConsultingSources() {
        assertTrue(view.servablePath(SITE, "/blog"));
        assertTrue(view.servablePath(SITE, "/about"), "taken by a page, but still a path content may use");
        assertFalse(view.servablePath(SITE, "/login"), "reserved kernel surface");
        assertFalse(view.servablePath(SITE, "/__admin/x"));
        assertFalse(view.servablePath(SITE, "/manifest.webmanifest"), "an exact endpoint route");
        assertFalse(view.servablePath(SITE, "/a/../b"), "unsafe");
        assertFalse(view.servablePath(SITE, "relative"));
        assertFalse(view.servablePath("nosuchsite", "/blog"), "an unknown site serves nothing");
    }

    @Test
    void unsafeSiteIdsAreRefusedAndUnknownOnesAnswerEmpty() {
        assertThrows(IllegalArgumentException.class, () -> view.entries("../etc"));
        assertThrows(IllegalArgumentException.class, () -> view.pageAt("a/b", "/x", Locale.ENGLISH));
        assertThrows(IllegalArgumentException.class, () -> view.servablePath("..", "/x"));
        assertThrows(IllegalArgumentException.class, () -> view.refresh("../x"));
        assertThrows(IllegalArgumentException.class, () -> view.indexableText("..", entry("about"), Locale.ENGLISH));
        assertEquals(List.of(), view.entries("nosuchsite"));
        assertEquals("", view.indexableText("nosuchsite", entry("about"), Locale.ENGLISH));
    }

    @Test
    void indexableTextIsTheSourcesMaterial() {
        assertTrue(view.indexableText(SITE, entry("solo"), ES).contains("texto solo"));
    }

    @Test
    void refreshDropsTheCachedListingForChangesNothingAnnounced() throws IOException {
        assertNotNull(entry("about"));
        // A page written by hand (SFTP, an editor on the server): no event fires.
        Files.writeString(templatesDir.resolve("manual.html"), "<p/>", StandardCharsets.UTF_8);
        Files.writeString(templatesDir.resolve("manual.meta.yml"), "path: /manual\n", StandardCharsets.UTF_8);
        assertEquals(null, entry("manual"), "the cached listing does not know it yet");
        view.refresh(SITE);
        assertNotNull(entry("manual"), "refresh made the next listing re-read the sources");
        Files.delete(templatesDir.resolve("manual.html"));
        Files.delete(templatesDir.resolve("manual.meta.yml"));
        view.refresh(SITE);
    }

    @Test
    void theLastSeenIndexOutlivesEvictionAndGoesWithTheSite() {
        view.entries(SITE);
        catalog.evict(SITE);
        assertEquals(List.of(new LocalizedUrl(Locale.ENGLISH, "/about", null), new LocalizedUrl(ES, "/es/acerca",
                null)), catalog.lastSeenUrls(SITE, "pages:about"), "eviction does not clear what was last seen");
        assertEquals(List.of(), catalog.lastSeenUrls(SITE, "pages:never"));
        assertEquals(List.of(), catalog.lastSeenUrls("unlisted", "pages:about"));

        events.publishEvent(new SiteChangedEvent("other", SiteChangedEvent.Kind.DELETED));
        assertFalse(catalog.lastSeenUrls(SITE, "pages:about").isEmpty(), "another site's deletion leaves it");
        events.publishEvent(new SiteChangedEvent(SITE, SiteChangedEvent.Kind.DELETED));
        assertEquals(List.of(), catalog.lastSeenUrls(SITE, "pages:about"), "the deleted site's index is dropped");
        view.entries(SITE); // re-list (the site's files are untouched by the bare event)
    }
}
