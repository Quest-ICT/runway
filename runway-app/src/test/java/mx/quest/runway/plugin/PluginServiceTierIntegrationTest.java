package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
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
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The SPI 0.4.0 service tier in a real boot: a plugin jar built at test time and loaded through the production loader
 * receives {@link PluginSettings} (constructor, field and {@code ObjectProvider} forms) and {@link PluginInfo} bound
 * to itself, its {@code PluginLifecycle} runs with its own {@link PluginInfo}, and — over a real Jetty — a throwing
 * head contributor or widget leaves the page {@code 200}, {@code no-store}, without validators, and never {@code 304}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PluginServiceTierIntegrationTest {

    private static final String CONFIG_SRC = """
            package fixtures.plugin.beta;
            import mx.quest.runway.spi.ContentWidget;
            import mx.quest.runway.spi.HeadContributor;
            import mx.quest.runway.spi.model.PluginInfo;
            import mx.quest.runway.spi.service.PluginSettings;
            import org.springframework.beans.factory.ObjectProvider;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class BetaConfig {
                @Autowired PluginInfo info;
                @Bean public BetaProbe betaProbe(PluginSettings settings, ObjectProvider<PluginSettings> lazy) {
                    return new BetaProbe(settings, lazy, info);
                }
                @Bean public BetaLifecycle betaLifecycle() { return new BetaLifecycle(); }
                @Bean public HeadContributor betaHead() { return new BetaHead(); }
                @Bean public ContentWidget betaWidget() { return new BetaWidget(); }
            }
            """;

    private static final String PROBE_SRC = """
            package fixtures.plugin.beta;
            import mx.quest.runway.spi.model.PluginInfo;
            import mx.quest.runway.spi.service.PluginSettings;
            import org.springframework.beans.factory.ObjectProvider;
            public class BetaProbe {
                private final PluginSettings settings; private final ObjectProvider<PluginSettings> lazy;
                private final PluginInfo info;
                public BetaProbe(PluginSettings settings, ObjectProvider<PluginSettings> lazy, PluginInfo info) {
                    this.settings = settings; this.lazy = lazy; this.info = info;
                }
                public String greeting(String site) { return settings.site(site).string("greeting", "code"); }
                public String lazyGreeting(String site) { return lazy.getObject().site(site).string("greeting", "code"); }
                public String secret(String site) { return settings.secret(site, "smtpPassword").orElse("none"); }
                public String info() { return info.id() + "@" + info.version(); }
            }
            """;

    private static final String LIFECYCLE_SRC = """
            package fixtures.plugin.beta;
            import mx.quest.runway.spi.model.PluginInfo;
            import mx.quest.runway.spi.service.PluginLifecycle;
            public class BetaLifecycle implements PluginLifecycle {
                public static volatile PluginInfo startedWith;
                public void started(PluginInfo self) { startedWith = self; }
            }
            """;

    private static final String HEAD_SRC = """
            package fixtures.plugin.beta;
            import mx.quest.runway.spi.HeadContributor;
            import mx.quest.runway.spi.model.HeadContext;
            public class BetaHead implements HeadContributor {
                public static volatile boolean explode;
                public String key() { return "beta.head"; }
                public String render(HeadContext c) {
                    if (explode) { throw new IllegalStateException("beta head boom"); }
                    return "<meta name=\\"beta\\" content=\\"ok\\">";
                }
            }
            """;

    private static final String WIDGET_SRC = """
            package fixtures.plugin.beta;
            import mx.quest.runway.spi.ContentWidget;
            import mx.quest.runway.spi.model.RenderFragment;
            import mx.quest.runway.spi.model.WidgetView;
            public class BetaWidget implements ContentWidget {
                public static volatile boolean explode;
                public String key() { return "beta"; }
                public RenderFragment render(WidgetView v) {
                    if (explode) { throw new IllegalStateException("beta widget boom"); }
                    return RenderFragment.of("<p>beta-widget</p>");
                }
            }
            """;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-k1-service-tier");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.build(external.resolve("plugins"), "beta", """
                    id: beta
                    version: 2.3.4
                    requiresSpi: ">=0.4.0"
                    mainClass: fixtures.plugin.beta.BetaConfig
                    siteSettings:
                      - { key: greeting, type: string, default: "hi" }
                      - { key: smtpPassword, type: secret }
                    """, Map.of("fixtures.plugin.beta.BetaConfig", CONFIG_SRC,
                    "fixtures.plugin.beta.BetaProbe", PROBE_SRC,
                    "fixtures.plugin.beta.BetaLifecycle", LIFECYCLE_SRC,
                    "fixtures.plugin.beta.BetaHead", HEAD_SRC,
                    "fixtures.plugin.beta.BetaWidget", WIDGET_SRC));
        }
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        for (String id : List.of("acme", "other")) {
            new SiteStore(layout, yaml).save(new Site(id, id, List.of(id.equals("acme") ? "localhost" : "other.test"),
                    Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        }
        Path plugins = Files.createDirectories(layout.siteDir("acme").resolve("plugins"));
        Files.writeString(plugins.resolve("beta.yml"), "greeting: hey\nsmtpPassword: plain-pw\n");
        Path t = Files.createDirectories(layout.siteDir("acme").resolve("templates"));
        Files.writeString(t.resolve("_master.html"),
                "<!DOCTYPE html><html><head><title>M</title></head><body><main id=\"content\">m</main></body></html>");
        Files.writeString(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        Files.writeString(t.resolve("home.html"), "<!DOCTYPE html><html><head><title>P</title></head><body>"
                + "<main id=\"content\"><h1>home</h1><div id=\"w\" data-widget=\"beta/main\">PH</div></main>"
                + "</body></html>", StandardCharsets.UTF_8);
        Files.writeString(t.resolve("home.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
        Files.writeString(t.resolve("home.meta.yml"), "path: /\nlocales: [en]\n");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Service-Tier-Secret-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "",
                true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ContributionGuard guard;
    @LocalServerPort
    private int port;

    private Object probe() {
        return context.getBean("betaProbe");
    }

    private Object call(String method, Object... args) throws Exception {
        Object probe = probe();
        Class<?>[] types = new Class<?>[args.length];
        java.util.Arrays.fill(types, String.class);
        Method m = probe.getClass().getMethod(method, types);
        return m.invoke(probe, args);
    }

    private static void setStatic(Object bean, String field, Object value) throws Exception {
        bean.getClass().getField(field).set(null, value);
    }

    @AfterEach
    void healthy() throws Exception {
        if (PluginFixture.compilerAvailable()) {
            setStatic(context.getBean("betaHead"), "explode", false);
            setStatic(context.getBean("betaWidget"), "explode", false);
        }
    }

    private HttpResponse<String> get(String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET();
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pluginSettingsAreBoundToTheInjectingPluginInEveryInjectionForm() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        assertEquals("hey", call("greeting", "acme"), "the stored value");
        assertEquals("hi", call("greeting", "other"), "the declared default for a site with no file");
        assertEquals("hey", call("lazyGreeting", "acme"), "an injected ObjectProvider binds to the plugin too");
        assertEquals("plain-pw", call("secret", "acme"), "a hand-written plaintext secret passes through");
        assertEquals("beta@2.3.4", call("info"), "PluginInfo through field injection");
    }

    @Test
    void aBareGetBeanHasNoInjectionPointAndFailsFast() {
        assumeTrue(PluginFixture.compilerAvailable());
        assertThrows(BeansException.class, () -> context.getBean(PluginSettings.class));
        assertThrows(BeansException.class, () -> context.getBean(PluginInfo.class));
    }

    @Test
    void theLifecycleHookStartedWithItsOwnPluginInfo() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Object started = context.getBean("betaLifecycle").getClass().getField("startedWith").get(null);
        assertNotNull(started, "started(PluginInfo) ran after refresh");
        assertEquals("beta", ((PluginInfo) started).id());
        assertEquals("2.3.4", ((PluginInfo) started).version());
        assertTrue(((PluginInfo) started).installDir().endsWith("beta"));
    }

    @Test
    void aHealthyPageIsCacheableAndRevalidates() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        HttpResponse<String> first = get(null);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("beta-widget") && first.body().contains("name=\"beta\""), first.body());
        String etag = first.headers().firstValue("ETag").orElse(null);
        assertNotNull(etag);
        assertEquals(304, get(etag).statusCode());
    }

    @Test
    void aThrowingHeadContributorLeavesThePageUpButUncacheable() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        String etag = get(null).headers().firstValue("ETag").orElseThrow();
        long before = guard.health("beta").failures();
        setStatic(context.getBean("betaHead"), "explode", true);

        HttpResponse<String> degraded = get(null);
        assertEquals(200, degraded.statusCode());
        assertTrue(degraded.body().contains("beta-widget"), "the rest of the page renders");
        assertFalse(degraded.body().contains("name=\"beta\""), "the failing head markup is absent");
        assertNull(degraded.headers().firstValue("ETag").orElse(null));
        assertNull(degraded.headers().firstValue("Last-Modified").orElse(null));
        assertEquals("no-store", degraded.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(guard.health("beta").failures() > before, "counted against the plugin");
        // A client holding the HEALTHY copy may still revalidate it: the 304 is decided before any contribution
        // runs and describes the healthy representation it already has. Only the degraded copy is never cacheable.
        assertEquals(304, get(etag).statusCode());
    }

    @Test
    void aThrowingWidgetLeavesThePageUpButUncacheable() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        setStatic(context.getBean("betaWidget"), "explode", true);

        HttpResponse<String> degraded = get(null);
        assertEquals(200, degraded.statusCode());
        assertFalse(degraded.body().contains("beta-widget"));
        assertTrue(degraded.body().contains("<h1>home</h1>"));
        assertNull(degraded.headers().firstValue("ETag").orElse(null));
        assertEquals("no-store", degraded.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(guard.health("beta").lastError().contains("beta widget boom"), guard.health("beta").lastError());
    }

    /**
     * The controller's own guard, with NO servlet filter in front of it (MockMvc runs no filters unless asked): the
     * head-injection filter is bypassed on a site with no head contributors and the CSP nonce feature off, so the
     * page controller must not stamp validators on a render that carried a substituted widget.
     */
    @Test
    void thePageControllerItselfServesASubstitutedWidgetUncacheable() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup((org.springframework.web.context.WebApplicationContext) context)
                .addFilters(new mx.quest.runway.site.SiteContextFilter(
                        context.getBean(mx.quest.runway.site.SiteResolver.class)))
                .build();
        org.springframework.mock.web.MockHttpServletResponse healthy = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")).andReturn().getResponse();
        assertNotNull(healthy.getHeader("ETag"), "the healthy render carries its validator");

        setStatic(context.getBean("betaWidget"), "explode", true);
        org.springframework.mock.web.MockHttpServletResponse degraded = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")).andReturn().getResponse();
        assertEquals(200, degraded.getStatus());
        assertNull(degraded.getHeader("ETag"));
        assertEquals("no-store", degraded.getHeader("Cache-Control"));
    }
}
