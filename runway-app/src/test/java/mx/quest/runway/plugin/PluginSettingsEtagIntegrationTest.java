package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.RunwayApplication;
import mx.quest.runway.reload.SiteReloadService;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * End to end (since 1.0.0-rc1), over real boots and a real Jetty: a plugin's <b>settings</b> shape what it renders
 * into a page,
 * so they move the page ETag — and they move it exactly when they take effect, with the body.
 *
 * <p>The fixture plugin {@code fx} mirrors the demo plugin's {@code analytics.write-key}: an app-level setting
 * ({@code external/plugin-settings/fx.yml}, bound once, applied by a restart) that adds a head script, and a per-site
 * setting ({@code sites/<id>/plugins/fx.yml}, read through {@code PluginSettings}) rendered into a head meta tag. The
 * plugin declares no {@code headInputs} — it needs none. Before 1.0.0-rc1 the ETag folded only the plugin's id and version,
 * so the restart with a key served the new script under the old ETag, and a client holding that ETag got {@code 304}.
 */
class PluginSettingsEtagIntegrationTest {

    private static final String CONFIG_SRC = """
            package fixtures.plugin.fx;
            import mx.quest.runway.spi.HeadContributor;
            import mx.quest.runway.spi.service.PluginSettings;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class FxConfig {
                @Bean public HeadContributor fxHead(@Value("${runway.plugin.fx.analytics.write-key:}") String key,
                                                    PluginSettings settings) {
                    return new FxHead(key, settings);
                }
            }
            """;

    private static final String HEAD_SRC = """
            package fixtures.plugin.fx;
            import mx.quest.runway.spi.HeadContributor;
            import mx.quest.runway.spi.model.HeadContext;
            import mx.quest.runway.spi.service.PluginSettings;
            public class FxHead implements HeadContributor {
                private final String key; private final PluginSettings settings;
                public FxHead(String key, PluginSettings settings) { this.key = key; this.settings = settings; }
                public String key() { return "fx.head"; }
                public String render(HeadContext c) {
                    String label = settings.site(c.site().id()).string("label", "none");
                    String meta = "<meta name=\\"fx-label\\" content=\\"" + label + "\\">";
                    return key.isBlank() ? meta
                            : meta + "<script src=\\"/fx/analytics.js\\" data-write-key=\\"" + key + "\\"></script>";
                }
            }
            """;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void seed(Path external) throws IOException {
        PluginFixture.build(external.resolve("plugins"), "fx", """
                id: fx
                version: 1.0.0
                requiresSpi: ">=1.0.0"
                mainClass: fixtures.plugin.fx.FxConfig
                settings:
                  - { key: analytics.write-key, type: string }
                siteSettings:
                  - { key: label, type: string, default: "none" }
                """, Map.of("fixtures.plugin.fx.FxConfig", CONFIG_SRC, "fixtures.plugin.fx.FxHead", HEAD_SRC));
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        Map<String, String> hosts = Map.of("acme", "localhost", "other", "127.0.0.1");
        for (Map.Entry<String, String> site : hosts.entrySet()) {
            new SiteStore(layout, yaml).save(new Site(site.getKey(), site.getKey(), List.of(site.getValue()),
                    Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
            Path t = layout.siteDir(site.getKey()).resolve("templates");
            write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body>"
                    + "<main id=\"content\">m</main></body></html>");
            write(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
            write(t.resolve("home.html"), "<!DOCTYPE html><html><head><title>P</title></head><body>"
                    + "<main id=\"content\"><h1>home</h1></main></body></html>");
            write(t.resolve("home.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                    + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
            write(t.resolve("home.meta.yml"), "path: /\nlocales: [en]\n");
        }
        write(layout.sitePluginConfigFile("acme", "fx"), "label: L1\n");
        write(layout.sitePluginConfigFile("other", "fx"), "label: O1\n");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Etag-Fold-Secret-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "",
                true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
    }

    private static ConfigurableApplicationContext boot(Path external) {
        return new SpringApplicationBuilder(RunwayApplication.class).properties(
                "server.port=0", "spring.main.banner-mode=off",
                "runway.storage.external=" + external,
                "runway.security.app-master-id=app-master").run();
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext context, String host, String ifNoneMatch)
            throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/")).GET();
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String etag(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("ETag").orElseThrow(() -> new AssertionError("no ETag"));
    }

    @Test
    void pluginSettingsMoveThePageEtagWhenTheyTakeEffect(@TempDir Path tmp) throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Path external = Files.createDirectories(tmp.resolve("external"));
        seed(external);

        // Boot 1: no app-level key.
        String withoutKey;
        String otherBefore;
        try (ConfigurableApplicationContext first = boot(external)) {
            HttpResponse<String> page = get(first, "localhost", null);
            withoutKey = etag(page);
            assertTrue(page.body().contains("content=\"L1\""), "the plugin is wired: " + page.body());
            assertFalse(page.body().contains("data-write-key"), page.body());
            otherBefore = etag(get(first, "127.0.0.1", null));
        }

        // The operator saves the key on the plugin's settings form (the file the UI writes), then restarts.
        write(external.resolve("plugin-settings").resolve("fx.yml"),
                "runway:\n  plugin:\n    fx:\n      analytics.write-key: K1\n");
        try (ConfigurableApplicationContext second = boot(external)) {
            HttpResponse<String> page = get(second, "localhost", withoutKey);
            assertEquals(200, page.statusCode(), "a client holding the pre-restart ETag must get the new body");
            String withKey = etag(page);
            assertNotEquals(withoutKey, withKey, "the app-level setting is folded into the ETag");
            assertTrue(page.body().contains("data-write-key=\"K1\""), "and the body carries the script: "
                    + page.body());
            assertEquals(304, get(second, "localhost", withKey).statusCode(), "the new pair revalidates");
            String otherWithKey = etag(get(second, "127.0.0.1", null));
            assertNotEquals(otherBefore, otherWithKey, "app-level settings apply to every site");

            // A per-site setting hand-edited on disk applies at the site reload — and so does its ETag move.
            write(external.resolve("sites").resolve("acme").resolve("plugins").resolve("fx.yml"), "label: L2\n");
            assertEquals(304, get(second, "localhost", withKey).statusCode(),
                    "not applied yet: the head still renders L1, so the old ETag still describes the body");
            assertTrue(second.getBean(SiteReloadService.class).reload("acme"));
            HttpResponse<String> reloaded = get(second, "localhost", withKey);
            assertEquals(200, reloaded.statusCode(), "applied: the old ETag no longer matches");
            assertNotEquals(withKey, etag(reloaded));
            assertTrue(reloaded.body().contains("content=\"L2\""), reloaded.body());
            assertEquals(otherWithKey, etag(get(second, "127.0.0.1", null)),
                    "one site's per-site settings never move another site's ETag");
        }
    }
}
