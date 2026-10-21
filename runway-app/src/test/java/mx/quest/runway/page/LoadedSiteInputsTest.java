package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.head.HeadContributorRegistry;
import mx.quest.runway.icon.IconHeadContributor;
import mx.quest.runway.icon.SiteIconService;
import mx.quest.runway.icon.SiteManifestService;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.plugin.SitePluginConfigService;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.seo.SeoConfigService;
import mx.quest.runway.seo.SeoHeadContributor;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Configuration a render reads from memory is folded into the page ETag <b>as loaded</b> — so the ETag moves
 * exactly when a change takes effect (a save, a site reload, a restart), together with the body, and never when a
 * file is merely edited on disk. Covers the per-site plugin settings (declared or not), the kernel head state
 * (SEO / icons / manifest), app-level plugin settings, and tenant isolation.
 */
class LoadedSiteInputsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Site ACME = site("acme");
    private static final Site GLOBEX = site("globex");
    private static final Page PAGE =
            new Page("home", "/home", Map.of(), List.of(EN), Map.of(), Map.of(), "", "_master", false, false, null, null);

    private static Site site(String id) {
        return new Site(id, id, List.of(id + ".test"), EN, List.of(EN), "https://" + id + ".test", SiteStatus.ACTIVE);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static RunningPlugins running(Path root, String... ids) {
        List<LoadedPlugin> plugins = new java.util.ArrayList<>();
        for (String id : ids) {
            // Every fixture plugin declares one per-site setting; "nosite" declares none.
            List<mx.quest.runway.plugin.PluginSettingDef> siteSettings = id.equals("nosite") ? null
                    : List.of(new mx.quest.runway.plugin.PluginSettingDef("label", "string", null, "Label", null));
            // (id, name, version, mainClass, enabled, essential, requires, conflicts, permissions, settings,
            //  siteSettings, widgetSettings, menu, dashboardCards, requiresSpi)
            plugins.add(new LoadedPlugin(new PluginDescriptor(id, id, "1.0.0", null, null, null, null, null, null,
                    null, siteSettings, null, null, null, null), root.resolve("plugins").resolve(id)));
        }
        return () -> List.copyOf(plugins);
    }

    /** The production collaborators over a temp data dir (two sites with byte-identical files). */
    private record Rig(StorageLayout layout, SitePluginConfigService pluginConfig, SeoConfigService seo,
                       SiteIconService icons, SiteManifestService manifest, RunningPlugins plugins) {

        LoadedSiteInputs inputs(org.springframework.core.env.Environment environment) {
            return new LoadedSiteInputs(null, seo, icons, manifest, pluginConfig, plugins, environment);
        }

        PageValidatorService validator(LoadedSiteInputs inputs, HeadContributor... contributors) {
            return new PageValidatorService(layout, plugins, new HeadContributorRegistry(List.of(contributors)),
                    null, null, inputs);
        }
    }

    private static Rig rig(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        for (String id : List.of("acme", "globex")) {
            Path t = layout.siteDir(id).resolve("templates");
            write(t.resolve("home.html"), "<html><body>home</body></html>");
            write(t.resolve("home.meta.yml"), "path: /home\nlocales: [en]\n");
            write(t.resolve("_master.html"), "<html><head></head><body>m</body></html>");
        }
        SitePluginConfigService pluginConfig = new SitePluginConfigService(layout,
                new AesGcmSecretCipher(new RunwaySecurityProperties()), event -> { });
        return new Rig(layout, pluginConfig, new SeoConfigService(layout, yaml),
                new SiteIconService(new SiteFileStore(layout)), new SiteManifestService(layout, yaml),
                running(tmp, "fx", "other", "nosite"));
    }

    // ---- per-site plugin settings ----------------------------------------------------------------------

    /**
     * A plugin's per-site settings shape what its widgets and head contributors emit, so they move the ETag — even
     * when the plugin declares nothing. They move it when the change APPLIES (here: the save's eviction event),
     * because that is when the render sees it; a hand edit alone changes neither.
     */
    @Test
    void aPerSitePluginSettingMovesTheEtagWhenItApplies(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        PageValidatorService service = rig.validator(rig.inputs(new MockEnvironment()));
        String before = service.compute(ACME, PAGE, EN).etag();

        write(rig.layout.sitePluginConfigFile("acme", "fx"), "label: Hello\n");
        assertEquals(before, service.compute(ACME, PAGE, EN).etag(),
                "an unapplied hand edit: the render still reads the cached settings, so must the ETag");

        rig.pluginConfig.onConfigChanged(new SitePluginConfigChangedEvent("acme", "fx"));
        String applied = service.compute(ACME, PAGE, EN).etag();
        assertNotEquals(before, applied, "the applied setting moves the ETag");
        assertEquals("Hello", rig.pluginConfig.config("acme", "fx").get("label"), "and is what the render reads");
        assertEquals(applied, service.compute(ACME, PAGE, EN).etag(), "stable while unchanged");
    }

    /** Tenant isolation: one site's plugin settings never move another site's ETag. */
    @Test
    void oneSitesPluginSettingsNeverMoveAnotherSitesEtag(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        PageValidatorService service = rig.validator(rig.inputs(new MockEnvironment()));
        String globex = service.compute(GLOBEX, PAGE, EN).etag();
        String acme = service.compute(ACME, PAGE, EN).etag();

        write(rig.layout.sitePluginConfigFile("acme", "fx"), "label: Hello\n");
        rig.pluginConfig.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED)); // a reload

        assertNotEquals(acme, service.compute(ACME, PAGE, EN).etag());
        assertEquals(globex, service.compute(GLOBEX, PAGE, EN).etag(), "the other site is untouched");
    }

    /**
     * A contributor that declares its own {@code plugins/<id>.yml} (as the docs once advised) no longer has the FILE
     * folded: the ETag would move on the hand edit while the head it renders, read through {@code PluginSettings},
     * stays on the cached values until the reload — the old head under the new ETag.
     */
    @Test
    void aDeclaredPluginConfigFileIsFoldedAsLoadedNotAsAFile(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        PageValidatorService service = rig.validator(rig.inputs(new MockEnvironment()),
                new Declaring("plugins/fx.yml"), new Declaring("./plugins/../plugins/fx.yml"));
        String before = service.compute(ACME, PAGE, EN).etag();

        write(rig.layout.sitePluginConfigFile("acme", "fx"), "label: Hello\n");
        assertEquals(before, service.compute(ACME, PAGE, EN).etag(), "the file alone moves nothing");

        rig.pluginConfig.onConfigChanged(new SitePluginConfigChangedEvent("acme", "fx"));
        assertNotEquals(before, service.compute(ACME, PAGE, EN).etag());
    }

    /** What the kernel does not load stays a file fold — a plugin's own file, a non-running plugin's config. */
    @Test
    void aDeclaredFileTheKernelDoesNotLoadIsStillFoldedAsAFile(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        PageValidatorService service = rig.validator(rig.inputs(new MockEnvironment()),
                new Declaring("fx/feed.yml"), new Declaring("plugins/absent-plugin.yml"));
        String before = service.compute(ACME, PAGE, EN).etag();

        write(rig.layout.siteDir("acme").resolve("fx").resolve("feed.yml"), "a: 1\n");
        String afterOwnFile = service.compute(ACME, PAGE, EN).etag();
        assertNotEquals(before, afterOwnFile, "a plugin's own declared file moves the ETag on edit");

        write(rig.layout.sitePluginConfigFile("acme", "absent-plugin"), "a: 1\n");
        assertNotEquals(afterOwnFile, service.compute(ACME, PAGE, EN).etag(),
                "a config file of a plugin that is not running is not loaded by anyone: folded as a file");
    }

    /**
     * Only plugins that declare {@code siteSettings:} are folded per site (a site manager can set nothing else; folding
     * every plugin would churn the settings cache on a many-tenant node). A plugin without the schema that declares
     * its file as a head input keeps the file fold.
     */
    @Test
    void perSiteSettingsFoldOnlyForPluginsThatDeclareThem(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        PageValidatorService undeclared = rig.validator(rig.inputs(new MockEnvironment()));
        String before = undeclared.compute(ACME, PAGE, EN).etag();
        write(rig.layout.sitePluginConfigFile("acme", "nosite"), "a: 1\n");
        rig.pluginConfig.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED));
        assertEquals(before, undeclared.compute(ACME, PAGE, EN).etag(), "no siteSettings: schema, nothing folded");

        PageValidatorService declaring = rig.validator(rig.inputs(new MockEnvironment()),
                new Declaring("plugins/nosite.yml"));
        String declared = declaring.compute(ACME, PAGE, EN).etag();
        write(rig.layout.sitePluginConfigFile("acme", "nosite"), "a: 2\n");
        assertNotEquals(declared, declaring.compute(ACME, PAGE, EN).etag(), "its declared file is folded as a file");
    }

    /** A virtual render (a ContentSource's entry) gets the same loaded-configuration folds as a page. */
    @Test
    void aVirtualRenderFoldsTheSameLoadedConfiguration(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        var entry = new mx.quest.runway.content.RoutableContent("blog",
                new mx.quest.runway.spi.model.ContentRef("e1", "/blog/e1", Map.of(), List.of(EN), Map.of(), Map.of(),
                        "", "__site__/acme/entry", true, java.time.Instant.ofEpochMilli(1000), false, null, null,
                        Map.of()));
        String none = rig.validator(rig.inputs(new MockEnvironment())).computeVirtual(ACME, entry, EN, null).etag();
        PageValidatorService keyed = rig.validator(rig.inputs(new MockEnvironment()
                .withProperty("runway.plugin.fx.analytics.write-key", "K1")));
        String withKey = keyed.computeVirtual(ACME, entry, EN, null).etag();
        assertNotEquals(none, withKey, "app-level settings");

        write(rig.layout.sitePluginConfigFile("acme", "fx"), "label: Hello\n");
        assertEquals(withKey, keyed.computeVirtual(ACME, entry, EN, null).etag(), "unapplied hand edit");
        rig.pluginConfig.onConfigChanged(new SitePluginConfigChangedEvent("acme", "fx"));
        String applied = keyed.computeVirtual(ACME, entry, EN, null).etag();
        assertNotEquals(withKey, applied, "per-site settings, once applied");
        assertNotEquals(applied, keyed.computeVirtual(ACME.withConfig("Renamed", EN, List.of(EN)), entry, EN, null)
                .etag(), "the site record");
    }

    // ---- kernel head state ----------------------------------------------------------------------------

    /**
     * The kernel's own head contributors read {@code seo.yml}, {@code manifest.yml} and the icon folder through
     * caches a save or a site reload refreshes; the ETag follows those caches, not the files.
     */
    @Test
    void theKernelHeadStateMovesTheEtagWhenItApplies(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        PageValidatorService service = rig.validator(rig.inputs(new MockEnvironment()),
                new SeoHeadContributor(), new IconHeadContributor());
        String before = service.compute(ACME, PAGE, EN).etag();
        String globex = service.compute(GLOBEX, PAGE, EN).etag();

        write(rig.layout.siteDir("acme").resolve("seo.yml"), "ogType: article\n");
        write(rig.layout.siteDir("acme").resolve("manifest.yml"), "themeColor: '#123456'\n");
        write(rig.layout.siteDir("acme").resolve("icons").resolve("favicon.svg"), "<svg/>");
        assertEquals(before, service.compute(ACME, PAGE, EN).etag(), "hand edits alone: the head is unchanged");

        SiteChangedEvent reload = new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED);
        rig.seo.onSiteChanged(reload);
        String seoApplied = service.compute(ACME, PAGE, EN).etag();
        assertNotEquals(before, seoApplied, "SEO applied");
        rig.manifest.onSiteChanged(reload);
        String manifestApplied = service.compute(ACME, PAGE, EN).etag();
        assertNotEquals(seoApplied, manifestApplied, "manifest applied");
        rig.icons.onSiteChanged(reload);
        assertNotEquals(manifestApplied, service.compute(ACME, PAGE, EN).etag(), "icon presence applied");

        assertEquals(globex, service.compute(GLOBEX, PAGE, EN).etag(), "another site's head is untouched");
    }

    // ---- app-level plugin settings ---------------------------------------------------------------------

    /**
     * App-level settings ({@code runway.plugin.<id>.*}, bound once through {@code @ConfigurationProperties}) shape a
     * plugin's output — the demo's analytics key adds a head script — so two runs with different values must not
     * share an ETag. Folding only the plugin's id/version would not tell them apart.
     */
    @Test
    void appLevelPluginSettingsMoveTheEtag(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        String none = rig.validator(rig.inputs(new MockEnvironment())).compute(ACME, PAGE, EN).etag();
        String k1 = rig.validator(rig.inputs(new MockEnvironment()
                .withProperty("runway.plugin.fx.analytics.write-key", "K1"))).compute(ACME, PAGE, EN).etag();
        String k2 = rig.validator(rig.inputs(new MockEnvironment()
                .withProperty("runway.plugin.fx.analytics.write-key", "K2"))).compute(ACME, PAGE, EN).etag();
        String k1Again = rig.validator(rig.inputs(new MockEnvironment()
                .withProperty("runway.plugin.fx.analytics.write-key", "K1"))).compute(ACME, PAGE, EN).etag();

        assertNotEquals(none, k1, "setting the key moves the ETag");
        assertNotEquals(k1, k2, "changing it does too");
        assertEquals(k1, k1Again, "the same settings fold identically (another node, the next restart)");
    }

    /** Only running plugins' own prefixes count, matched by whole name elements ({@code fx} is not {@code fxx}). */
    @Test
    void onlyARunningPluginsOwnPrefixFolds(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        String none = rig.validator(rig.inputs(new MockEnvironment())).compute(ACME, PAGE, EN).etag();
        MockEnvironment unrelated = new MockEnvironment()
                .withProperty("runway.plugin.fxx.label", "x")
                .withProperty("runway.plugin.not-running.label", "x")
                .withProperty("runway.http-cache.max-age", "60");

        assertEquals(none, rig.validator(rig.inputs(unrelated)).compute(ACME, PAGE, EN).etag());
    }

    /** The highest-precedence source wins, as in binding; an environment variable (relaxed name) counts too. */
    @Test
    void theEffectiveValueFoldsIncludingAnEnvironmentVariable(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        MockEnvironment fileOnly = new MockEnvironment().withProperty("runway.plugin.fx.label", "file");
        String fromFile = rig.validator(rig.inputs(fileOnly)).compute(ACME, PAGE, EN).etag();

        MockEnvironment overridden = new MockEnvironment().withProperty("runway.plugin.fx.label", "file");
        overridden.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("RUNWAY_PLUGIN_FX_LABEL", "env")));
        assertNotEquals(fromFile, rig.validator(rig.inputs(overridden)).compute(ACME, PAGE, EN).etag(),
                "an environment variable overriding the file changes what binds, so the ETag");
    }

    /**
     * Folded once: the values are bound once, at startup, and a save on the settings form applies on restart — so
     * the token describes what the running plugins have, not a file saved since (that would move every ETag before
     * the change shows). It is a digest: a secret never sits in it in clear.
     */
    @Test
    void appLevelSettingsAreFoldedOnceAsDigest(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp);
        MockEnvironment environment = new MockEnvironment().withProperty("runway.plugin.fx.secret", "s3cr3t");
        LoadedSiteInputs inputs = rig.inputs(environment);
        String token = inputs.pluginAppSettings();
        assertTrue(token.matches("[0-9a-f]{64}"), "a SHA-256 digest");
        assertTrue(!token.contains("s3cr3t"));

        environment.setProperty("runway.plugin.fx.secret", "changed-after-startup");
        assertEquals(token, inputs.pluginAppSettings(), "computed once, like the binding it describes");
    }

    // ---- canonical form ---------------------------------------------------------------------------------

    /** Equal values fold equally whatever the map order — {@code Map.of} order is salted per JVM run. */
    @Test
    void theCanonicalFormIgnoresMapOrderButNotValues() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("a", 1);
        one.put("b", List.of("x", "y"));
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("b", List.of("x", "y"));
        other.put("a", 1);

        assertEquals(CanonicalFingerprint.of(one), CanonicalFingerprint.of(other));
        assertNotEquals(CanonicalFingerprint.of(one), CanonicalFingerprint.of(Map.of("a", 1, "b", List.of("y", "x"))),
                "list order is meaningful");
        assertNotEquals(CanonicalFingerprint.of(Map.of("a", "b;c")), CanonicalFingerprint.of(Map.of("a;b", "c")),
                "a separator inside a value cannot fake a boundary");
        assertNotEquals(CanonicalFingerprint.of(ACME), CanonicalFingerprint.of(GLOBEX));
        assertEquals(CanonicalFingerprint.of(ACME), CanonicalFingerprint.of(site("acme")));
    }

    // ---- template params, widget-instance config, the URL map -------------------------------------------------------

    /** A content source whose enumeration a test swaps, standing in for a plugin's cached index. */
    private static final class Source implements mx.quest.runway.spi.ContentSource {
        volatile List<mx.quest.runway.spi.model.ContentRef> refs = List.of(ref("/posts/one", "One"));

        static mx.quest.runway.spi.model.ContentRef ref(String path, String title) {
            return new mx.quest.runway.spi.model.ContentRef("p1", path, Map.of(), List.of(EN), Map.of("en", title),
                    Map.of(), "", "__site__/acme/post", true, null, false, null, null, Map.of());
        }

        @Override public String key() {
            return "posts";
        }

        @Override public List<mx.quest.runway.spi.model.ContentRef> enumerate(mx.quest.runway.spi.model.SiteView s) {
            return "acme".equals(s.id()) ? refs : List.of();
        }

        @Override public mx.quest.runway.spi.model.ContentBody body(mx.quest.runway.spi.model.SiteView s, String id,
                                                                     Locale locale) {
            return null;
        }
    }

    private record DataRig(StorageLayout layout, mx.quest.runway.site.SiteParamsService params,
                           mx.quest.runway.widget.WidgetInstanceConfigService widgets, PageStore pages, Source source,
                           PageValidatorService validator) {
    }

    private static DataRig dataRig(Path tmp) throws IOException {
        Rig base = rig(tmp);
        YamlStore yaml = new YamlStore();
        for (String id : List.of("acme", "globex")) {
            Path t = base.layout.siteDir(id).resolve("templates");
            write(t.resolve("about.html"), "<html><body>about</body></html>");
            write(t.resolve("about.meta.yml"), "path: /about\nlocales: [en]\ntitle:\n  en: About\n");
        }
        mx.quest.runway.site.SiteParamsService params = new mx.quest.runway.site.SiteParamsService(base.layout, yaml);
        mx.quest.runway.widget.WidgetInstanceConfigService widgets = new mx.quest.runway.widget
                .WidgetInstanceConfigService(base.layout, new AesGcmSecretCipher(new RunwaySecurityProperties()));
        PageStore pages = new PageStore(base.layout, yaml);
        Source source = new Source();
        mx.quest.runway.content.ContentCatalog catalog = new mx.quest.runway.content.ContentCatalog(
                new mx.quest.runway.content.ContentSourceRegistry(List.of(source)));
        LoadedSiteInputs inputs = new LoadedSiteInputs(null, null, null, null, null, null, null, params, widgets,
                pages, catalog);
        return new DataRig(base.layout, params, widgets, pages, source,
                new PageValidatorService(base.layout, null, null, null, null, inputs));
    }

    @Test
    void templateParamsMoveTheEtagWhenTheyApplyAndOnlyForTheirSite(@TempDir Path tmp) throws IOException {
        DataRig rig = dataRig(tmp);
        String acme = rig.validator.compute(ACME, PAGE, EN).etag();
        String globex = rig.validator.compute(GLOBEX, PAGE, EN).etag();

        rig.params.save("acme", Map.of("phone", "555"));
        String saved = rig.validator.compute(ACME, PAGE, EN).etag();
        assertNotEquals(acme, saved, "a console save applies at once: the ETag moves");
        assertEquals(globex, rig.validator.compute(GLOBEX, PAGE, EN).etag(), "another site is untouched");

        write(rig.layout.siteDir("acme").resolve("params.yml"), "phone: '999'\n");
        assertEquals(saved, rig.validator.compute(ACME, PAGE, EN).etag(), "a hand edit applies at the reload, not before");
        rig.params.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED));
        assertNotEquals(saved, rig.validator.compute(ACME, PAGE, EN).etag(), "the reload applies it");
    }

    @Test
    void widgetInstanceConfigMovesTheEtagWhenItAppliesAndOnlyForItsSite(@TempDir Path tmp) throws IOException {
        DataRig rig = dataRig(tmp);
        String acme = rig.validator.compute(ACME, PAGE, EN).etag();
        String globex = rig.validator.compute(GLOBEX, PAGE, EN).etag();

        rig.widgets.save("acme", "carousel", "home",
                List.of(new mx.quest.runway.plugin.PluginSettingDef("interval", "int", null, "Interval", null)),
                Map.of("interval", "7"));
        String saved = rig.validator.compute(ACME, PAGE, EN).etag();
        assertNotEquals(acme, saved);
        assertEquals(globex, rig.validator.compute(GLOBEX, PAGE, EN).etag());

        write(rig.layout.widgetInstancesFile("acme"), "carousel/home:\n  interval: 9\n");
        assertEquals(saved, rig.validator.compute(ACME, PAGE, EN).etag(), "a hand edit applies at the reload");
        rig.widgets.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.SUSPENDED));
        assertEquals(saved, rig.validator.compute(ACME, PAGE, EN).etag(), "a suspend is not a reload");
        rig.widgets.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED));
        assertNotEquals(saved, rig.validator.compute(ACME, PAGE, EN).etag(), "the reload applies it");
        assertEquals(9, rig.widgets.config("acme", "carousel", "home").get("interval"), "and the render reads it");
    }

    /** The URL map reads every page's slug, publication, paths and locales — and nothing else of another page. */
    @Test
    void anotherPagesUrlMovesTheEtagButItsTitleDoesNot(@TempDir Path tmp) throws IOException {
        DataRig rig = dataRig(tmp);
        Path about = rig.layout.siteDir("acme").resolve("templates").resolve("about.meta.yml");
        String before = rig.validator.compute(ACME, PAGE, EN).etag();
        String globex = rig.validator.compute(GLOBEX, PAGE, EN).etag();

        write(about, "path: /about\nlocales: [en]\ntitle:\n  en: About us, renamed\n");
        assertEquals(before, rig.validator.compute(ACME, PAGE, EN).etag(),
                "another page's title is not in this page's render: no URL changed");

        write(about, "path: /about-us\nlocales: [en]\ntitle:\n  en: About us, renamed\n");
        String moved = rig.validator.compute(ACME, PAGE, EN).etag();
        assertNotEquals(before, moved, "${urls['about']} changed");
        assertEquals(globex, rig.validator.compute(GLOBEX, PAGE, EN).etag(), "another site is untouched");

        write(about, "path: /about-us\nlocales: [en]\ndraft: true\ntitle:\n  en: About us, renamed\n");
        assertNotEquals(moved, rig.validator.compute(ACME, PAGE, EN).etag(), "unpublishing drops it from the map");

        Path t = rig.layout.siteDir("acme").resolve("templates");
        write(t.resolve("contact.meta.yml"), "path: /contact\nlocales: [en]\n");
        String added = rig.validator.compute(ACME, PAGE, EN).etag();
        Files.delete(t.resolve("contact.meta.yml"));
        assertNotEquals(added, rig.validator.compute(ACME, PAGE, EN).etag(), "a page appearing or leaving moves it");
    }

    @Test
    void aContentSourcesEnumerationIsAnInputOfTheUrlMap(@TempDir Path tmp) throws IOException {
        DataRig rig = dataRig(tmp);
        String before = rig.validator.compute(ACME, PAGE, EN).etag();
        String globex = rig.validator.compute(GLOBEX, PAGE, EN).etag();

        rig.source.refs = List.of(Source.ref("/posts/one", "One")); // a new list, equal content
        assertEquals(before, rig.validator.compute(ACME, PAGE, EN).etag(), "folded by content, not by instance");

        rig.source.refs = List.of(Source.ref("/posts/first", "One"));
        assertNotEquals(before, rig.validator.compute(ACME, PAGE, EN).etag());
        assertEquals(globex, rig.validator.compute(GLOBEX, PAGE, EN).etag());
    }

    /** A contributor that declares one path and renders nothing. */
    private record Declaring(String path) implements HeadContributor {
        @Override
        public String key() {
            return "declaring." + path;
        }

        @Override
        public String render(HeadContext context) {
            return "";
        }

        @Override
        public List<String> headInputs(String siteId) {
            return List.of(path);
        }
    }
}
