package mx.quest.runway.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import mx.quest.runway.backup.BackupService;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageChangedEvent;
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
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The event delivery guarantees in a real boot, with the real page cache, the real page and backup locks and every
 * kernel listener: plugin callbacks run <b>after</b> every kernel listener, <b>never</b>
 * under a host lock, still <b>before the write returns</b>; a throwing callback never fails the save; a deletion carries
 * the old URL; a callback that writes a page neither deadlocks nor loses its own events. Old-style plugin
 * {@code @EventListener}s on the host events keep receiving them.
 *
 * <p>The probes are registered the way the plugin loader registers a plugin (bean definitions before refresh), so
 * they precede every kernel bean in registration order — the strongest form of the ordering question.
 */
@SpringBootTest
@ContextConfiguration(initializers = SpiEventDispatchIntegrationTest.EarlyProbes.class)
class SpiEventDispatchIntegrationTest {

    private static final String SITE = "dispatch";
    private static final String PW = "Dispatch-Probe-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-k3-dispatch");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site(SITE, "Dispatch", List.of("dispatch.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        Path templates = Files.createDirectories(layout.siteDir(SITE).resolve("templates"));
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        new UserStore(yaml).save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root",
                "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** Registers the probes before refresh — the plugin loader's registration path, hence first in order. */
    public static class EarlyProbes implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) context.getBeanFactory();
            registry.registerBeanDefinition("aaaThrowingListener", new RootBeanDefinition(Throwing.class));
            registry.registerBeanDefinition("aabRecordingListener", new RootBeanDefinition(Recording.class));
            registry.registerBeanDefinition("aacLegacyListener", new RootBeanDefinition(Legacy.class));
            registry.registerBeanDefinition("zzzKernelLate", new RootBeanDefinition(KernelLate.class));
        }
    }

    /** One shared, ordered log of who ran when. */
    static final List<String> TRACE = new CopyOnWriteArrayList<>();

    /** A plugin-shaped listener that always throws. */
    public static class Throwing implements SiteEventListener {
        @Override
        public void onCatalogChanged(CatalogChange change) {
            throw new IllegalStateException("throwing listener");
        }

        @Override
        public void onSiteLifecycle(SiteLifecycleEvent event) {
            throw new IllegalStateException("throwing listener");
        }
    }

    /** A plugin-shaped listener that records what it sees and asserts the delivery conditions from inside. */
    public static class Recording implements SiteEventListener {
        final List<CatalogChange> changes = new CopyOnWriteArrayList<>();
        final List<SiteLifecycleEvent> lifecycle = new CopyOnWriteArrayList<>();
        final List<String> violations = new CopyOnWriteArrayList<>();
        volatile PageWriteService writer;
        volatile BackupService backups;
        volatile ContentCatalog catalog;
        volatile SiteStore sites;
        volatile boolean echo;

        @Override
        public void onSiteLifecycle(SiteLifecycleEvent event) {
            lifecycle.add(event);
            checkNoLock(event.siteId(), null);
        }

        @Override
        public void onCatalogChanged(CatalogChange change) {
            TRACE.add("plugin:" + change.cause() + ":" + change.id());
            changes.add(change);
            checkNoLock(change.siteId(), change.id());
            if (change.cause() == CatalogChange.Cause.PAGE && change.id() != null && catalog != null) {
                // after the kernel: the catalog re-enumerated here reflects the change
                boolean listed = catalog.enumerate(SiteViews.toView(sites.find(change.siteId()).orElseThrow()))
                        .stream().anyMatch(item -> item.ref().id().equals(change.id()));
                TRACE.add("listed:" + change.id() + "=" + listed);
            }
            if (echo && "trigger".equals(change.id())) {
                // re-entrancy: a callback that writes a page
                writer.save(SITE, page("echo", "/echo"), "<p/>", null);
                TRACE.add("echo-saved");
            }
        }

        private void checkNoLock(String siteId, String slug) {
            if (HostWriteScope.depth() != 0) {
                violations.add("a write scope is open: depth " + HostWriteScope.depth());
            }
            if (writer != null && slug != null && writer.isPageLockHeldByCurrentThread(siteId, slug)) {
                violations.add("the page lock of " + slug + " is held");
            }
            if (backups != null && backups.isSiteLockHeldByCurrentThread(siteId)) {
                violations.add("the backup lock of " + siteId + " is held");
            }
        }
    }

    /** An unmigrated plugin: listens to the host event directly, at the historical default order. */
    public static class Legacy {
        final List<PageChangedEvent> seen = new CopyOnWriteArrayList<>();

        @EventListener
        public void onPageChanged(PageChangedEvent event) {
            seen.add(event);
        }
    }

    /** A stand-in for the last kernel band: plugin dispatch must come after it. */
    public static class KernelLate {
        @EventListener
        @Order(KernelEventOrder.KERNEL_LATE)
        public void onPageChanged(PageChangedEvent event) {
            TRACE.add("kernel-late:" + event.slug());
        }
    }

    @Autowired
    private PageWriteService writer;
    @Autowired
    private BackupService backups;
    @Autowired
    private ContentCatalog catalog;
    @Autowired
    private SiteStore sites;
    @Autowired
    private Recording recording;
    @Autowired
    private Legacy legacy;
    @Autowired
    private SpiEventBridge bridge;

    @BeforeEach
    void wire() {
        recording.writer = writer;
        recording.backups = backups;
        recording.catalog = catalog;
        recording.sites = sites;
        recording.echo = false;
        recording.changes.clear();
        recording.lifecycle.clear();
        recording.violations.clear();
        legacy.seen.clear();
        TRACE.clear();
    }

    @AfterEach
    void noViolations() {
        assertEquals(List.of(), recording.violations, "a callback ran under a host lock or inside a write scope");
        assertEquals(0, HostWriteScope.depth());
    }

    private static Page page(String slug, String path) {
        return new Page(slug, path, Map.of(), List.of(), Map.of("en", "T"), Map.of(), "", "_master", false);
    }

    @Test
    void theBridgeCollectedTheProbesAndKeptRegistrationOrder() {
        List<String> keys = bridge.listeners().stream().map(r -> r.key()).toList();
        assertEquals(List.of(Throwing.class.getName(), Recording.class.getName()), keys);
    }

    @Test
    void aSaveDeliversAfterEveryKernelListenerAndBeforeItReturns() {
        writer.save(SITE, page("news", "/news"), "<p/>", null);

        // returned: the callback has already run
        assertEquals(1, recording.changes.size());
        CatalogChange change = recording.changes.get(0);
        assertEquals(CatalogChange.Cause.PAGE, change.cause());
        assertEquals("pages", change.sourceKey());
        assertEquals("news", change.id());
        assertTrue(change.local());
        assertEquals(List.of(new LocalizedUrl(Locale.ENGLISH, "/news", null)), change.currentUrls());
        assertEquals(List.of("kernel-late:news", "plugin:PAGE:news", "listed:news=true"), TRACE,
                "the plugin dispatch ran after the last kernel band and saw the new page in a fresh listing");
        assertEquals(1, legacy.seen.size(), "an unmigrated plugin's host-event listener still receives the event");
    }

    @Test
    void aDeletedPageReportsItsOldUrlAndNoCurrentOne() {
        writer.save(SITE, page("doomed", "/doomed"), "<p/>", null);
        catalog.enumerate(SiteViews.toView(sites.find(SITE).orElseThrow())); // the site has been listed
        recording.changes.clear();

        assertTrue(writer.delete(SITE, "doomed"));

        CatalogChange change = recording.changes.get(0);
        assertEquals("doomed", change.id());
        assertEquals(List.of(new LocalizedUrl(Locale.ENGLISH, "/doomed", null)), change.previousUrls(),
                "the deletion still names the URL it took away (what IndexNow submits)");
        assertEquals(List.of(), change.currentUrls());
    }

    @Test
    void aRenameReportsBothChangesAfterItCompleted() {
        writer.save(SITE, page("before", "/before"), "<p/>", null);
        catalog.enumerate(SiteViews.toView(sites.find(SITE).orElseThrow()));
        recording.changes.clear();
        TRACE.clear();

        writer.rename(SITE, "before", "after");

        assertEquals(List.of("before", "after"), recording.changes.stream().map(CatalogChange::id).toList());
        assertEquals("/before", recording.changes.get(0).previousUrls().get(0).path());
        assertEquals(List.of("kernel-late:before", "kernel-late:after", "plugin:PAGE:before", "listed:before=false",
                "plugin:PAGE:after", "listed:after=true"), TRACE,
                "both kernel dispatches ran inside the rename; the plugin saw them once it had completed");
    }

    @Test
    void aThrowingListenerNeverFailsTheSave() {
        assertDoesNotThrow(() -> writer.save(SITE, page("sturdy", "/sturdy"), "<p/>", null));
        assertEquals(1, recording.changes.size(), "the listener after the throwing one still ran");
    }

    @Test
    void aCallbackThatWritesAPageNeitherDeadlocksNorLosesItsOwnEvent() {
        recording.echo = true;
        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                writer.save(SITE, page("trigger", "/trigger"), "<p/>", null));
        List<String> ids = recording.changes.stream().map(CatalogChange::id).toList();
        assertEquals(List.of("trigger", "echo"), ids,
                "the echo write's event is delivered after the callback that made it, before the outer save returned");
        assertTrue(TRACE.indexOf("echo-saved") < TRACE.indexOf("plugin:PAGE:echo"),
                "never nested inside the callback that wrote it");
    }

    @Test
    void anEditorialSaveUnderThePageLockDeliversOnlyAfterTheLock() {
        writer.withPageLock(SITE, "locked", () -> {
            writer.save(SITE, page("locked", "/locked"), "<p/>", null);
            assertTrue(recording.changes.isEmpty(), "nothing delivered while the editorial lock is held");
            return null;
        });
        assertEquals(1, recording.changes.size());
    }

    @Test
    void aRestoreDeliversAfterTheBackupLockIsReleased() throws IOException {
        writer.save(SITE, page("kept", "/kept"), "<p/>", null);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        backups.backup(SITE, zip);
        recording.lifecycle.clear();

        backups.restore(SITE, new ByteArrayInputStream(zip.toByteArray()));

        assertEquals(1, recording.lifecycle.size());
        assertEquals(SiteLifecycleEvent.Kind.RESTORED, recording.lifecycle.get(0).kind());
        assertTrue(recording.lifecycle.get(0).wholesale());
    }

    @Test
    void anEventOutsideAnyWriteScopeIsDeliveredInlineAfterTheKernel() {
        // A reload publishes UPDATED with no lock and no scope: delivered at once, still after every kernel listener.
        events.publishEvent(new SiteChangedEvent(SITE, SiteChangedEvent.Kind.UPDATED));
        assertEquals(List.of(SiteLifecycleEvent.Kind.UPDATED), recording.lifecycle.stream()
                .map(SiteLifecycleEvent::kind).toList());
        assertFalse(recording.lifecycle.get(0).fromBackup());
        assertTrue(recording.lifecycle.get(0).local());
    }

    @Autowired
    private org.springframework.context.ApplicationEventPublisher events;
}
