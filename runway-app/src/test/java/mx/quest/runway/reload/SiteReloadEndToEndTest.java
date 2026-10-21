package mx.quest.runway.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The reload endpoint against a REAL server, driven the way an operator without a console works: edit
 * {@code site.yml} on disk with no console, then make the change take effect without restarting.
 *
 * <p>The load-bearing case is {@link #anOutOfBandSiteYmlEditTakesEffectAfterAReload()} — it first proves the
 * defect (a hand-edit is silently ignored) and then proves the endpoint fixes it, in one test, so neither half can
 * rot into a false pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteReloadEndToEndTest {

    private static final String TOKEN = "reload-endpoint-secret";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-reload");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        YamlStore yaml = new YamlStore();
        new SiteStore(StorageLayout.from(props), yaml)
                .save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                        null, SiteStatus.ACTIVE, false, null));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Reload-Test-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.reload.token", () -> TOKEN);
        registry.add("runway.health.content-check", () -> "false");
    }

    @Autowired
    private SiteStore sites;

    @Autowired
    private mx.quest.runway.plugin.SitePluginConfigService pluginConfig;

    @LocalServerPort
    private int port;

    private HttpResponse<String> post(String path, String token) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> health() throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/__health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Rewrites site.yml directly on disk — exactly what a file-only operator's edit wrapper does. */
    private static void editSiteYmlOutOfBand(String yaml) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        Path file = StorageLayout.from(props).siteDir("acme").resolve("site.yml");
        Files.writeString(file, yaml);
    }

    /** Writes sites/<id>/plugins/<plugin>.yml directly on disk — the file-only operator's edit. */
    private static void editPluginConfigOutOfBand(String pluginId, String yaml) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        Files.createDirectories(layout.sitePluginConfigDir("acme"));
        Files.writeString(layout.sitePluginConfigFile("acme", pluginId), yaml);
    }

    @Test
    void anOutOfBandSiteYmlEditTakesEffectAfterAReload() throws Exception {
        assertTrue(health().body().contains("\"status\":\"up\""), "precondition: the site serves");

        // The operator edits the file directly. No console, no restart.
        editSiteYmlOutOfBand("""
                id: acme
                name: Acme
                hosts: [localhost]
                defaultLocale: en
                locales: [en]
                status: ACTIVE
                maintenance: true
                maintenanceMessage: back soon
                """);

        // THE DEFECT: the edit is silently ignored — this is what caused a false negative on a live deployment.
        assertTrue(health().body().contains("\"status\":\"up\""),
                "an out-of-band edit is expected to be inert until reloaded; if this fails the caching changed");

        // THE FIX: reload, no restart.
        HttpResponse<String> reload = post("/__reload/site/acme", TOKEN);
        assertEquals(200, reload.statusCode(), reload.body());
        assertTrue(reload.body().contains("\"reloaded\":\"acme\""), reload.body());

        assertTrue(health().body().contains("\"status\":\"down\""),
                "after the reload the hand-edited maintenance flag must be in force");
    }

    /**
     * The defect found on a live deployment: a reload reported success while per-site
     * <b>plugin</b> config
     * kept serving the pre-edit file, because {@code SitePluginConfigService} invalidated only on
     * {@code isWholesaleReplacement()} (delete/restore) and reload publishes {@code UPDATED}.
     *
     * <p><b>The priming read on the first line is the whole test.</b> Without it nothing is cached, the later
     * read goes to disk anyway, and the test passes just as happily against the broken code — the exact shape of
     * false-green that let this ship. The middle assertion then proves the priming actually took, so neither half
     * can rot into a vacuous pass.
     *
     * <p>Deliberately asserted through {@link mx.quest.runway.plugin.SitePluginConfigService#config} rather than
     * by re-reading the file: the file was never stale, the cache in front of it was.
     */
    @Test
    void anOutOfBandPluginConfigEditTakesEffectAfterAReload() throws Exception {
        // PRIME the cache. Everything below is meaningless without this line.
        assertEquals(Map.of(), pluginConfig.config("acme", "discoverability"),
                "precondition: no per-site plugin config yet");

        editPluginConfigOutOfBand("discoverability", """
                robots:
                  extra-disallow: [/private]
                """);

        // THE DEFECT: cached, so the hand-edit is invisible. (Also proves the priming read really cached.)
        assertEquals(Map.of(), pluginConfig.config("acme", "discoverability"),
                "an out-of-band plugin-config edit is expected to be inert until reloaded");

        HttpResponse<String> reload = post("/__reload/site/acme", TOKEN);
        assertEquals(200, reload.statusCode(), reload.body());

        // THE FIX: the reload must reach the per-site plugin config cache, not just SiteStore.
        Object robots = pluginConfig.config("acme", "discoverability").get("robots");
        assertNotNull(robots, "after the reload the hand-edited plugin config must be visible");
        assertTrue(String.valueOf(robots).contains("/private"), String.valueOf(robots));
    }

    @Autowired
    private org.thymeleaf.TemplateEngine templateEngine;

    /** Writes sites/<id>/templates/reloadprobe.html directly on disk (static: no decoupled logic needed). */
    private static void writeProbeTemplate(String siteId, String version) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        Path file = StorageLayout.from(props).siteDir(siteId).resolve("templates").resolve("reloadprobe.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<!DOCTYPE html><html><head><title>t</title></head><body><p>" + version
                + "</p></body></html>");
    }

    private String renderProbe(String siteId) {
        return templateEngine.process(mx.quest.runway.web.SiteTemplateResolver.renderSpec(siteId,
                        mx.quest.runway.web.SiteTemplateResolver.PREFIX + siteId + "/reloadprobe"),
                new org.thymeleaf.context.Context(Locale.ENGLISH));
    }

    private boolean isProbeCached(String siteId) {
        String name = mx.quest.runway.web.SiteTemplateResolver.PREFIX + siteId + "/reloadprobe";
        return templateEngine.getCacheManager().getTemplateCache().keySet().stream()
                .anyMatch(key -> name.equals(key.getTemplate()));
    }

    /**
     * A template file edited on disk shows on the next render with no reload at all (a cached parse is valid only
     * while its file is unchanged), and a reload still evicts that site's parsed templates — and only that site's.
     * The cache-membership checks prove the cache is on and warm, so neither half can pass vacuously.
     */
    @Test
    void anOutOfBandTemplateEditShowsAtOnceAndAReloadEvictsOnlyThatSite() throws Exception {
        writeProbeTemplate("acme", "ACME-V1");
        writeProbeTemplate("acme2", "OTHER-V1"); // "acme2" also proves the eviction prefix ends at a '/'
        assertTrue(renderProbe("acme").contains("ACME-V1"), "prime");
        assertTrue(renderProbe("acme2").contains("OTHER-V1"), "prime the other site");
        assertTrue(isProbeCached("acme") && isProbeCached("acme2"), "the cache is on and warm");

        writeProbeTemplate("acme", "ACME-V2");
        assertTrue(renderProbe("acme").contains("ACME-V2"), "an on-disk edit needs no reload to show");

        assertEquals(200, post("/__reload/site/acme", TOKEN).statusCode());

        assertTrue(!isProbeCached("acme"), "the reload evicted the reloaded site's parsed templates");
        assertTrue(isProbeCached("acme2"), "the other site's parsed template stayed cached");
        assertTrue(renderProbe("acme2").contains("OTHER-V1"));
    }

    /** A reload of one site must not blow away another tenant's warm plugin-config entries. */
    @Test
    void aReloadEvictsOnlyTheReloadedSite() throws Exception {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        Files.createDirectories(layout.sitePluginConfigDir("other"));
        Files.writeString(layout.sitePluginConfigFile("other", "search"), "indexing:\n  enabled: true\n");

        assertNotEquals(Map.of(), pluginConfig.config("other", "search"), "precondition: other site is warm");

        assertEquals(200, post("/__reload/site/acme", TOKEN).statusCode());

        assertNotEquals(Map.of(), pluginConfig.config("other", "search"),
                "reloading acme must not evict another tenant's plugin config");
    }

    @Test
    void theEndpointIsInvisibleWithoutATokenAndRefusesAWrongOne() throws Exception {
        // Mutating endpoint: a wrong token must FAIL, unlike the public health probe which degrades silently.
        assertEquals(403, post("/__reload/site/acme", "wrong-token").statusCode());
        assertEquals(403, post("/__reload/site/acme", null).statusCode());
    }

    @Test
    void unknownSitesAndBadMethodsAreRejected() throws Exception {
        assertEquals(404, post("/__reload/site/nosuchsite", TOKEN).statusCode());
        HttpResponse<String> get = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__reload/site/acme"))
                        .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void reloadingEverySiteReportsWhatItReloaded() throws Exception {
        HttpResponse<String> res = post("/__reload/sites", TOKEN);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"acme\""), res.body());
        assertTrue(res.body().contains("\"count\":1"), res.body());
    }

    @Test
    void theReloadIsNotChargedToTheTenant() throws Exception {
        // An operator's reload must not consume the tenant's bandwidth quota or distort its traffic graph.
        assertTrue(mx.quest.runway.web.UnmeteredPaths.isUnmetered("/__reload/site/acme"));
        assertNotEquals(true, mx.quest.runway.web.UnmeteredPaths.isUnmetered("/some-tenant-page"));
    }
}
