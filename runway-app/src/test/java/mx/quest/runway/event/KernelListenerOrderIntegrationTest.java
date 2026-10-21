package mx.quest.runway.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.content.RoutableContent;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The regression test for the live defect the listener-order bands fixed: with the <b>real</b> page record cache, a
 * listener at {@link KernelEventOrder#PLUGIN_DISPATCH} that re-enumerates the content catalog inside a page-change
 * dispatch must see the change — and must not leave a stale listing cached behind it.
 *
 * <p>The probe is registered exactly the way the plugin loader registers a plugin: as a bean definition added
 * <b>before refresh</b>, so it precedes every component-scanned kernel bean in registration order. That is the
 * condition under which the defect was live — a plugin consumer at the default (lowest) order ran before the page
 * record caches, which also sat at the default order, rebuilt the catalog from stale {@code Page} records, and the
 * catalog's eviction epoch then kept that stale list for its 30-second window.
 *
 * <p>Deletion alone does NOT discriminate (the pages source lists the templates folder, so a deleted file is
 * absent whatever the record cache holds); an <b>update</b> does — a page made draft, or moved to a new path, is
 * served from the cached {@code Page} record until the record cache is evicted. Both are asserted.
 */
@SpringBootTest
@ContextConfiguration(initializers = KernelListenerOrderIntegrationTest.EarlyProbe.class)
class KernelListenerOrderIntegrationTest {

    private static final String SITE = "orders";
    private static final String PW = "Order-Probe-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-listener-order");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site(SITE, "Orders", List.of("orders.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        Path templates = Files.createDirectories(layout.siteDir(SITE).resolve("templates"));
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        new UserStore(yaml).save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root",
                "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** Registers {@link Probe} before refresh — the plugin loader's registration path, hence first in order. */
    public static class EarlyProbe implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            ((BeanDefinitionRegistry) context.getBeanFactory())
                    .registerBeanDefinition("aaaOrderProbe", new RootBeanDefinition(Probe.class));
        }
    }

    /** A plugin-shaped consumer: on every page change it re-enumerates the catalog (as IndexNow and search do). */
    public static class Probe {
        private final ContentCatalog catalog;
        private final SiteStore sites;
        final List<List<RoutableContent>> seen = new CopyOnWriteArrayList<>();

        public Probe(ContentCatalog catalog, SiteStore sites) {
            this.catalog = catalog;
            this.sites = sites;
        }

        @EventListener
        @Order(KernelEventOrder.PLUGIN_DISPATCH)
        public void onPageChanged(PageChangedEvent event) {
            seen.add(catalog.enumerate(SiteViews.toView(sites.find(event.siteId()).orElseThrow())));
        }
    }

    @Autowired
    private PageWriteService writer;
    @Autowired
    private ContentCatalog catalog;
    @Autowired
    private SiteStore sites;
    @Autowired
    private Probe probe;

    private SiteView view;

    @BeforeEach
    void setUp() {
        view = SiteViews.toView(sites.find(SITE).orElseThrow());
    }

    private static Page page(String slug, String path, boolean draft) {
        return new Page(slug, path, Map.of(), List.of(), Map.of("en", "T"), Map.of(), "", "_master", draft, false,
                null, null);
    }

    private static RoutableContent find(List<RoutableContent> listing, String slug) {
        return listing.stream().filter(item -> item.ref().id().equals(slug)).findFirst().orElse(null);
    }

    @Test
    void aPluginDispatchListenerSeesAPageMadeDraftAndNoStaleListingIsCached() {
        writer.save(SITE, page("news", "/news", false), "<html/>", null);
        assertNotNull(find(catalog.enumerate(view), "news"), "published page listed (and the catalog now cached)");
        probe.seen.clear();

        writer.setDraft(SITE, "news", true);

        assertEquals(1, probe.seen.size(), "the probe ran inside the page-change dispatch");
        assertFalse(probe.seen.get(0).stream().anyMatch(item -> item.ref().id().equals("news")),
                "a listener at PLUGIN_DISPATCH must see the draft — the page record cache was evicted first");
        assertFalse(catalog.enumerate(view).stream().anyMatch(item -> item.ref().id().equals("news")),
                "and no stale listing may stay cached behind the dispatch");
    }

    @Test
    void aPluginDispatchListenerSeesAPathChange() {
        writer.save(SITE, page("about", "/about", false), "<html/>", null);
        assertEquals("/about", find(catalog.enumerate(view), "about").pathFor(Locale.ENGLISH));
        probe.seen.clear();

        writer.save(SITE, page("about", "/about-us", false), "<html/>", null);

        assertEquals(1, probe.seen.size());
        assertEquals("/about-us", find(probe.seen.get(0), "about").pathFor(Locale.ENGLISH),
                "the listener must see the new path, not the cached record's old one");
        assertEquals("/about-us", find(catalog.enumerate(view), "about").pathFor(Locale.ENGLISH));
    }

    @Test
    void aPluginDispatchListenerDoesNotSeeADeletedPage() {
        writer.save(SITE, page("doomed", "/doomed", false), "<html/>", null);
        assertNotNull(find(catalog.enumerate(view), "doomed"));
        probe.seen.clear();

        assertTrue(writer.delete(SITE, "doomed"));

        assertEquals(1, probe.seen.size());
        assertFalse(probe.seen.get(0).stream().anyMatch(item -> item.ref().id().equals("doomed")));
        assertFalse(catalog.enumerate(view).stream().anyMatch(item -> item.ref().id().equals("doomed")));
    }
}
