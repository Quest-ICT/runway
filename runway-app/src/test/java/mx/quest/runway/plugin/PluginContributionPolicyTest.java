package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.backup.BackupParticipantRegistry;
import mx.quest.runway.content.ContentSourceRegistry;
import mx.quest.runway.endpoint.EndpointContributorRegistry;
import mx.quest.runway.head.HeadContributorRegistry;
import mx.quest.runway.quality.PageQualityCheckRegistry;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.widget.ContentWidgetRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contribution collisions, unsupported routes and containment against classes that really
 * come from plugin jars, so {@link PluginOwnership} attributes them by code source exactly as in production.
 *
 * <p>Two fixture plugins load in the order {@code p1, p2}. Each jar carries one class per axis, parameterised by
 * key so a test can make them collide at will.
 */
class PluginContributionPolicyTest {

    @TempDir
    static Path root;
    private static URLClassLoader loader;
    private static PluginOwnership ownership;
    private static final SiteView SITE = new SiteView("s1", "Site", Locale.ENGLISH, List.of(Locale.ENGLISH), null);
    private static final PageMeta PAGE = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH,
            true, false);

    private static String sources(String pkg) {
        return """
                package %1$s;
                import java.util.List;
                import mx.quest.runway.spi.*;
                import mx.quest.runway.spi.model.*;
                public class Head implements HeadContributor {
                    private final String key; private final boolean fail;
                    public Head(String key, boolean fail) { this.key = key; this.fail = fail; }
                    public String key() { return key; }
                    public String render(HeadContext c) {
                        if (fail) { throw new IllegalStateException("head boom"); }
                        return "<!--" + key + "@%1$s-->";
                    }
                    public List<String> headInputs(String siteId) {
                        if (fail) { throw new IllegalStateException("inputs boom"); }
                        return List.of("seo.yml");
                    }
                }
                """.formatted(pkg);
    }

    private static String widget(String pkg) {
        return """
                package %1$s;
                import mx.quest.runway.spi.*;
                import mx.quest.runway.spi.model.*;
                public class Widget implements ContentWidget {
                    private final String key; private final int mode;
                    public Widget(String key, int mode) { this.key = key; this.mode = mode; }
                    public String key() { return key; }
                    public RenderFragment render(WidgetView v) {
                        if (mode == 1) { throw new RuntimeException("widget boom"); }
                        if (mode == 2) { return null; }
                        return RenderFragment.of("<b>" + key + "</b>");
                    }
                }
                """.formatted(pkg);
    }

    private static String check(String pkg) {
        return """
                package %1$s;
                import java.util.List;
                import mx.quest.runway.spi.*;
                import mx.quest.runway.spi.model.*;
                public class Check implements PageQualityCheck {
                    private final String key;
                    public Check(String key) { this.key = key; }
                    public String key() { return key; }
                    public List<Finding> inspect(PageView p) { throw new UnsupportedOperationException("check boom"); }
                }
                """.formatted(pkg);
    }

    private static String routes(String pkg) {
        return """
                package %1$s;
                import java.util.List;
                import mx.quest.runway.spi.*;
                import mx.quest.runway.spi.model.*;
                public class Routes implements EndpointContributor {
                    private final String key; private final List<String> paths;
                    public Routes(String key, List<String> paths) { this.key = key; this.paths = paths; }
                    public String key() { return key; }
                    public List<Route> routes() {
                        return paths.stream().map(p -> p.startsWith("APP ")
                                ? Route.get(p.substring(4), Realm.APPLICATION) : Route.get(p, Realm.TENANT)).toList();
                    }
                    public RouteResponse handle(RouteRequest r) { return RouteResponse.text(200, key); }
                }
                """.formatted(pkg);
    }

    private static LoadedPlugin loaded(String id) {
        PluginDescriptor d = PluginDescriptor.read(new ByteArrayInputStream(
                ("id: " + id + "\nversion: 1.0.0\nmainClass: x.Y\n").getBytes(StandardCharsets.UTF_8)));
        return new LoadedPlugin(d, root.resolve(id));
    }

    @BeforeAll
    static void buildPlugins() throws IOException {
        if (!PluginFixture.compilerAvailable()) {
            return;
        }
        for (String id : List.of("p1", "p2")) {
            String pkg = "fixtures.f11." + id;
            PluginFixture.build(root, id, "id: " + id + "\nversion: 1.0.0\n",
                    Map.of(pkg + ".Head", sources(pkg), pkg + ".Widget", widget(pkg), pkg + ".Check", check(pkg),
                            pkg + ".Routes", routes(pkg)));
        }
        // A library jar under p1/lib — shared code, owned by nobody.
        PluginFixture.build(root.resolve("scratch"), "libby", "id: libby\nversion: 1.0.0\n",
                Map.of("fixtures.f11.lib.Shared", "package fixtures.f11.lib; public class Shared { public Object field; }"));
        Files.createDirectories(root.resolve("p1/lib"));
        Files.move(root.resolve("scratch/libby/libby.jar"), root.resolve("p1/lib/libby.jar"));

        loader = new URLClassLoader(new URL[] {root.resolve("p1/p1.jar").toUri().toURL(),
                root.resolve("p2/p2.jar").toUri().toURL(), root.resolve("p1/lib/libby.jar").toUri().toURL()},
                PluginContributionPolicyTest.class.getClassLoader());
        ownership = PluginOwnership.of(new PluginRuntime(List.of(loaded("p1"), loaded("p2")), loader, Map.of(),
                false));
    }

    private static ContributionGuard guard() {
        return new ContributionGuard(ownership, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T make(String plugin, String simpleName, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = Class.forName("fixtures.f11." + plugin + "." + simpleName, true, loader);
        return (T) type.getConstructor(types).newInstance(args);
    }

    private static HeadContributor head(String plugin, String key, boolean fail) throws Exception {
        return make(plugin, "Head", new Class<?>[] {String.class, boolean.class}, key, fail);
    }

    private static HeadContributor kernelHead(String key) {
        return new HeadContributor() {
            public String key() {
                return key;
            }

            public String render(HeadContext context) {
                return "<!--kernel-->";
            }
        };
    }

    // ---- ownership ----------------------------------------------------------------------------------------

    @Test
    void ownershipFollowsTheJarAClassWasLoadedFrom() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        assertEquals("p1", ownership.ownerOf(head("p1", "k", false).getClass()).pluginId().orElseThrow());
        assertEquals("p2", ownership.ownerOf(head("p2", "k", false).getClass()).pluginId().orElseThrow());
        PluginOwnership.Owner lib = ownership.ownerOf(Class.forName("fixtures.f11.lib.Shared", false, loader));
        assertEquals(PluginOwnership.Owner.Kind.LIBRARY, lib.kind(), "a lib/ jar belongs to no plugin");
        assertTrue(lib.pluginId().isEmpty());
        assertEquals(PluginOwnership.Owner.Kind.NONE, ownership.ownerOf(getClass()).kind(), "a test class");
        assertEquals(PluginOwnership.Owner.Kind.NONE, ownership.ownerOf(String.class).kind(), "the JDK");
        assertEquals(0, ownership.loadIndex("p1"));
        assertEquals(1, ownership.loadIndex("p2"));
        assertEquals(Integer.MAX_VALUE, ownership.loadIndex("absent"));
    }

    @Test
    void ownershipComparesRealPathsSoASymlinkedPluginsFolderStillMatches() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Path link = root.getParent().resolve(root.getFileName() + "-link");
        Files.deleteIfExists(link);
        try {
            Files.createSymbolicLink(link, root);
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            assumeTrue(false, "no symlink support");
        }
        try {
            PluginDescriptor d = loaded("p1").descriptor();
            PluginOwnership viaLink = PluginOwnership.of(new PluginRuntime(
                    List.of(new LoadedPlugin(d, link.resolve("p1"))), loader, Map.of(), false));
            assertEquals("p1", viaLink.ownerOf(head("p1", "k", false).getClass()).pluginId().orElseThrow());
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ---- contribution collisions ------------------------------------------------------------------------------------

    @Test
    void aPluginCollidingWithTheKernelIsRefusedAndMarkedDegraded() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        HeadContributorRegistry registry = new HeadContributorRegistry(
                List.of(head("p1", "seo", false), kernelHead("seo")), guard);
        HeadContext context = new HeadContext(SITE, PAGE, Locale.ENGLISH, null, null, null, false, false);
        assertEquals("<!--kernel-->", registry.render(context), "the kernel contributor keeps the key");
        assertTrue(guard.health("p1").degraded().get(0).contains("key 'seo'"), guard.health("p1").toString());

        // The same with the kernel contribution first in bean order: the plugin still loses.
        ContributionGuard second = guard();
        HeadContributorRegistry kernelFirst = new HeadContributorRegistry(
                List.of(kernelHead("seo"), head("p2", "seo", false)), second);
        assertEquals("<!--kernel-->", kernelFirst.render(context));
        assertTrue(second.health("p2").hasProblems());
        assertFalse(second.health(ContributionGuard.KERNEL).hasProblems(), "the kernel is never the refused side");
    }

    @Test
    void betweenTwoPluginsTheLaterInLoadOrderIsRefusedWhateverTheBeanOrder() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        // Bean order p2 first; load order p1 first — the load order decides.
        HeadContributorRegistry registry = new HeadContributorRegistry(
                List.of(head("p2", "dup", false), head("p1", "dup", false)), guard);
        HeadContext context = new HeadContext(SITE, PAGE, Locale.ENGLISH, null, null, null, false, false);
        assertEquals("<!--dup@fixtures.f11.p1-->", registry.render(context));
        assertTrue(guard.health("p2").hasProblems());
        assertFalse(guard.health("p1").hasProblems());
    }

    @Test
    void everyRegistryAppliesTheSameRule() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        ContentWidget w1 = make("p1", "Widget", new Class<?>[] {String.class, int.class}, "box", 0);
        ContentWidget w2 = make("p2", "Widget", new Class<?>[] {String.class, int.class}, "box", 0);
        ContentWidgetRegistry widgets = new ContentWidgetRegistry(List.of(w2, w1), guard);
        assertEquals("<b>box</b>", widgets.render("box", new WidgetView(SITE, PAGE, Locale.ENGLISH, "i", false,
                Map.of())).html());
        PageQualityCheck c1 = make("p1", "Check", new Class<?>[] {String.class}, "dup");
        PageQualityCheck c2 = make("p2", "Check", new Class<?>[] {String.class}, "dup");
        new PageQualityCheckRegistry(List.of(c1, c2), guard);
        assertEquals(2, guard.health("p2").degraded().size(), "widget and check refused for p2");
        assertTrue(guard.health("p1").degraded().isEmpty());
        // Kernel-only registries still fail fast, as before.
        assertThrows(IllegalStateException.class,
                () -> new HeadContributorRegistry(List.of(kernelHead("k"), kernelHead("k")), guard));
        assertTrue(new ContentSourceRegistry(List.of(), guard).isEmpty());
        assertTrue(new BackupParticipantRegistry(List.of(), guard).all().isEmpty());
    }

    @Test
    void aPluginBlankKeyIsRefusedButAKernelBlankKeyFailsStartup() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        HeadContributorRegistry registry = new HeadContributorRegistry(List.of(head("p1", " ", false)), guard);
        assertTrue(registry.isEmpty());
        assertTrue(guard.health("p1").degraded().get(0).contains("blank key"));
        assertThrows(IllegalStateException.class,
                () -> new HeadContributorRegistry(List.of(kernelHead(" ")), guard));
    }

    // ---- unsupported routes and collisions for routes ---------------------------------------------------------------

    @Test
    void badPluginRoutesAreRefusedAloneAndTheRestLoad() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        EndpointContributor r1 = make("p1", "Routes", new Class<?>[] {String.class, List.class}, "p1.routes",
                List.of("/p1/ok", "APP /p1/app", "/p1/*", "/p1/{id}", "/__admin/steal", "/shared.txt"));
        EndpointContributor r2 = make("p2", "Routes", new Class<?>[] {String.class, List.class}, "p2.routes",
                List.of("/shared.txt", "/p2/ok"));
        EndpointContributorRegistry registry = new EndpointContributorRegistry(List.of(r2, r1), guard);

        assertTrue(registry.match("GET", "/p1/ok").isPresent());
        assertTrue(registry.match("GET", "/p2/ok").isPresent());
        assertFalse(registry.match("GET", "/p1/app").isPresent(), "APPLICATION realm refused");
        assertFalse(registry.match("GET", "/p1/*").isPresent(), "wildcard refused");
        assertFalse(registry.match("GET", "/p1/{id}").isPresent(), "template refused");
        assertFalse(registry.match("GET", "/__admin/steal").isPresent(), "reserved prefix refused");
        assertEquals("p1.routes", registry.match("GET", "/shared.txt").orElseThrow().contributor().key(),
                "the earlier plugin in load order keeps a contested route");
        assertEquals(4, guard.health("p1").degraded().size(), guard.health("p1").toString());
        assertEquals(1, guard.health("p2").degraded().size(), guard.health("p2").toString());
    }

    @Test
    void aKernelRouteBreakingTheContractStillFailsStartup() {
        EndpointContributor kernel = new EndpointContributor() {
            public String key() {
                return "kernel.routes";
            }

            public List<mx.quest.runway.spi.model.Route> routes() {
                return List.of(mx.quest.runway.spi.model.Route.get("/x/*", mx.quest.runway.spi.model.Realm.TENANT));
            }

            public mx.quest.runway.spi.model.RouteResponse handle(mx.quest.runway.spi.model.RouteRequest r) {
                return mx.quest.runway.spi.model.RouteResponse.text(200, "");
            }
        };
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new EndpointContributorRegistry(List.of(kernel)));
        assertTrue(failure.getMessage().contains("reserved pattern character"), failure.getMessage());
    }

    // ---- containment per axis --------------------------------------------------------------------------------------

    @Test
    void aThrowingHeadContributorIsSubstitutedAndTheRenderSaysDegraded() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        HeadContributorRegistry registry = new HeadContributorRegistry(
                List.of(head("p1", "bad", true), head("p2", "good", false)), guard);
        HeadContext context = new HeadContext(SITE, PAGE, Locale.ENGLISH, null, null, null, false, false);
        HeadContributorRegistry.Rendered rendered = registry.renderChecked(context);
        assertEquals("<!--good@fixtures.f11.p2-->", rendered.markup());
        assertTrue(rendered.degraded());
        assertEquals(List.of("seo.yml"), registry.declaredInputs("s1"), "the failing contributor adds no inputs");
        assertEquals(2, guard.health("p1").failures(), "render + headInputs, both counted against p1");
        assertEquals(0, guard.health("p2").failures());
    }

    @Test
    void aThrowingOrNullWidgetRendersEmpty() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        ContentWidget throwing = make("p1", "Widget", new Class<?>[] {String.class, int.class}, "t", 1);
        ContentWidget nulling = make("p1", "Widget", new Class<?>[] {String.class, int.class}, "n", 2);
        ContentWidgetRegistry registry = new ContentWidgetRegistry(List.of(throwing, nulling), guard);
        WidgetView view = new WidgetView(SITE, PAGE, Locale.ENGLISH, "i", false, Map.of());
        assertEquals(RenderFragment.empty(), registry.render("t", view));
        assertEquals(RenderFragment.empty(), registry.render("n", view));
        assertEquals(2, guard.health("p1").failures());
    }

    @Test
    void aThrowingQualityCheckBecomesOneWarningThatCannotBlockAPublish() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        ContributionGuard guard = guard();
        PageQualityCheck check = make("p1", "Check", new Class<?>[] {String.class}, "p1.check");
        PageQualityCheckRegistry registry = new PageQualityCheckRegistry(List.of(check), guard);
        List<Finding> findings = registry.inspect(new PageView(SITE, PAGE, Locale.ENGLISH, null, "<html/>"));
        assertEquals(1, findings.size());
        assertEquals(mx.quest.runway.spi.model.Severity.WARNING, findings.get(0).severity());
        assertEquals(PageQualityCheckRegistry.CHECK_FAILED, findings.get(0).code());
        assertTrue(findings.get(0).message().contains("p1.check"));
        assertEquals(1, guard.health("p1").failures());
    }

    // ---- plugin-scoped injection: bound by code source, fail fast otherwise --------------------------

    /** The kernel's real factory over a config store in a temp folder. */
    private static PluginScopedServices scoped() {
        mx.quest.runway.storage.StorageProperties props = new mx.quest.runway.storage.StorageProperties();
        props.setExternal(root.resolve("external"));
        SitePluginConfigService config = new SitePluginConfigService(
                mx.quest.runway.storage.StorageLayout.from(props), null, event -> { });
        return new PluginScopedServices(ownership, config, null, mx.quest.runway.storage.StorageLayout.from(props),
                1024);
    }

    private static org.springframework.beans.factory.InjectionPoint pointIn(Class<?> type, String field)
            throws Exception {
        return new org.springframework.beans.factory.InjectionPoint(type.getDeclaredField(field));
    }

    @Test
    void theFactoryHandsAPluginItsOwnAppLevelSecretFromTheRunningEnvironmentAndNothingElse() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        PluginDescriptor withSecret = PluginDescriptor.read(new ByteArrayInputStream((
                "id: p1\nversion: 1.0.0\nmainClass: x.Y\nsettings:\n"
                        + "  - { key: apiKey, type: secret, label: l }\n"
                        + "  - { key: host, type: string, label: l }\n").getBytes(StandardCharsets.UTF_8)));
        PluginOwnership owners = PluginOwnership.of(new PluginRuntime(
                List.of(new LoadedPlugin(withSecret, root.resolve("p1")), loaded("p2")), loader, Map.of(), false));
        mx.quest.runway.storage.StorageProperties props = new mx.quest.runway.storage.StorageProperties();
        props.setExternal(root.resolve("external"));
        mx.quest.runway.storage.StorageLayout layout = mx.quest.runway.storage.StorageLayout.from(props);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("runway.plugin.p1.apiKey", "p1-key")
                .withProperty("runway.plugin.p1.host", "db.example")
                .withProperty("runway.plugin.p2.apiKey", "p2-key");
        PluginScopedServices scoped = new PluginScopedServices(owners,
                new SitePluginConfigService(layout, null, event -> { }), null, layout, 1024, env);

        PluginSettings p1 = scoped.pluginSettings(pointIn(head("p1", "k", false).getClass(), "key"));
        PluginSettings p2 = scoped.pluginSettings(pointIn(head("p2", "k", false).getClass(), "key"));
        assertEquals(Optional.of("p1-key"), p1.appSecret("apiKey"), "the running value of its own declared secret");
        assertEquals(Optional.empty(), p1.appSecret("host"), "a non-secret app setting is never handed out here");
        assertEquals(Optional.empty(), p2.appSecret("apiKey"),
                "p2 declared no app-level secret, so the environment's value is not handed out");
    }

    @Test
    void anInjectionPointInAPluginsOwnJarBindsToThatPlugin() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        PluginScopedServices scoped = scoped();
        Class<?> p1 = head("p1", "k", false).getClass();
        Class<?> p2 = head("p2", "k", false).getClass();
        assertEquals("p1", ((mx.quest.runway.plugin.spi.PluginSettingsImpl) scoped.pluginSettings(pointIn(p1, "key")))
                .pluginId());
        assertEquals("p2", ((mx.quest.runway.plugin.spi.PluginSettingsImpl) scoped.pluginSettings(pointIn(p2, "key")))
                .pluginId());
        assertTrue(scoped.pluginSettings(pointIn(p1, "key")) == scoped.pluginSettings(pointIn(p1, "fail")),
                "one facade per plugin");
        assertEquals("p2", scoped.pluginInfo(pointIn(p2, "key")).id());
    }

    @Test
    void injectionIntoALibraryClassFailsFastAndSaysWhy() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Class<?> shared = Class.forName("fixtures.f11.lib.Shared", false, loader);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> scoped().pluginSettings(pointIn(shared, "field")));
        assertTrue(failure.getMessage().contains("lib/ jar"), failure.getMessage());
        assertThrows(IllegalStateException.class, () -> scoped().pluginInfo(pointIn(shared, "field")));
    }

    /** A kernel (or test) class holding the injection point. */
    static final class KernelHolder {
        Object field;
    }

    @Test
    void injectionIntoKernelCodeFailsFastAndNamesTheKernelAlternative() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> scoped().pluginSettings(pointIn(KernelHolder.class, "field")));
        assertTrue(failure.getMessage().contains("SitePluginConfigService"), failure.getMessage());
    }

    // ---- PluginSiteFiles is plugin-scoped the same way -------------------------------------------------

    @Test
    void pluginSiteFilesBindToTheInjectingPluginsOwnSubtree() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        PluginScopedServices scoped = scoped();
        var p1 = (mx.quest.runway.plugin.spi.PluginSiteFilesImpl) scoped.pluginSiteFiles(
                pointIn(head("p1", "k", false).getClass(), "key"));
        var p2 = (mx.quest.runway.plugin.spi.PluginSiteFilesImpl) scoped.pluginSiteFiles(
                pointIn(head("p2", "k", false).getClass(), "key"));
        assertEquals("p1", p1.pluginId());
        assertEquals("p2", p2.pluginId());
        assertTrue(p1 == scoped.pluginSiteFiles(pointIn(head("p1", "k", false).getClass(), "fail")),
                "one facade per plugin");
        java.nio.file.Files.createDirectories(root.resolve("external/sites/s1"));
        p1.writeText("s1", "data.txt", "from p1");
        assertTrue(java.nio.file.Files.isRegularFile(root.resolve("external/sites/s1/plugins/p1/data.txt")),
                "the subtree is named by the BOUND plugin, never by an argument");
        assertEquals(java.util.Optional.empty(), p2.readText("s1", "data.txt"));
    }

    @Test
    void pluginSiteFilesInjectionFailsFastOutsideAPluginsOwnJar() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Class<?> shared = Class.forName("fixtures.f11.lib.Shared", false, loader);
        assertTrue(assertThrows(IllegalStateException.class, () -> scoped().pluginSiteFiles(pointIn(shared, "field")))
                .getMessage().contains("lib/ jar"));
        IllegalStateException kernel = assertThrows(IllegalStateException.class,
                () -> scoped().pluginSiteFiles(pointIn(KernelHolder.class, "field")));
        assertTrue(kernel.getMessage().contains("SiteFileStore"), kernel.getMessage());
    }
}
