package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import mx.quest.runway.console.OperatorShellModel;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.context.WebApplicationContext;

/**
 * The SPI 0.6.0 service tier in a real boot, through a plugin jar built at test time and loaded by the production loader:
 * {@code ContentChanges} bound to its plugin (own source accepted, a kernel source refused), a plugin
 * {@code SiteEventListener} attributed to its plugin (settings changes of its own plugin only; the content change it
 * announced comes back with its plugin id), {@code ContentCatalogView} injected into plugin code, and a
 * {@code @ConsoleScreen} controller receiving exactly the shell model a kernel screen gets — while an unannotated
 * plugin controller gets none.
 */
@SpringBootTest
class PluginEventTierIntegrationTest {

    private static final String CONFIG_SRC = """
            package fixtures.plugin.gamma;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import mx.quest.runway.spi.service.ContentCatalogView;
            import mx.quest.runway.spi.service.ContentChanges;
            @Configuration
            public class GammaConfig {
                @Bean public GammaSource gammaSource() { return new GammaSource(); }
                @Bean public GammaListener gammaListener() { return new GammaListener(); }
                @Bean public GammaProbe gammaProbe(ContentChanges changes, ContentCatalogView view) {
                    return new GammaProbe(changes, view);
                }
                @Bean public GammaScreen gammaScreen() { return new GammaScreen(); }
                @Bean public GammaBare gammaBare() { return new GammaBare(); }
            }
            """;

    private static final String SOURCE_SRC = """
            package fixtures.plugin.gamma;
            import java.util.*;
            import mx.quest.runway.spi.ContentSource;
            import mx.quest.runway.spi.model.*;
            public class GammaSource implements ContentSource {
                public String key() { return "gamma"; }
                public List<ContentRef> enumerate(SiteView site) {
                    return List.of(ContentRef.of("one", "/gamma/one", Map.of(), List.of(), Map.of(), Map.of(),
                            "__plugin__/gamma/entry", null));
                }
                public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
            }
            """;

    private static final String LISTENER_SRC = """
            package fixtures.plugin.gamma;
            import java.util.*;
            import java.util.concurrent.CopyOnWriteArrayList;
            import mx.quest.runway.spi.model.*;
            import mx.quest.runway.spi.service.SiteEventListener;
            public class GammaListener implements SiteEventListener {
                public static final List<Object> SEEN = new CopyOnWriteArrayList<>();
                public void onSettingsChanged(SettingsChange change) { SEEN.add(change); }
                public void onCatalogChanged(CatalogChange change) { SEEN.add(change); }
            }
            """;

    private static final String PROBE_SRC = """
            package fixtures.plugin.gamma;
            import java.util.*;
            import mx.quest.runway.spi.service.ContentCatalogView;
            import mx.quest.runway.spi.service.ContentChanges;
            public class GammaProbe {
                private final ContentChanges changes; private final ContentCatalogView view;
                public GammaProbe(ContentChanges changes, ContentCatalogView view) {
                    this.changes = changes; this.view = view;
                }
                public void announce(String site, String key, String id) { changes.changed(site, key, id); }
                public List<String> composites(String site) {
                    return view.entries(site).stream().map(e -> e.compositeId()).toList();
                }
            }
            """;

    private static final String SCREEN_SRC = """
            package fixtures.plugin.gamma;
            import java.util.TreeSet;
            import mx.quest.runway.spi.service.ConsoleScreen;
            import org.springframework.stereotype.Controller;
            import org.springframework.ui.Model;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.ResponseBody;
            @Controller
            @ConsoleScreen
            public class GammaScreen {
                @GetMapping("/__app/gamma")
                @ResponseBody
                public String screen(Model model) { return String.join(",", new TreeSet<>(model.asMap().keySet())); }
            }
            """;

    private static final String BARE_SRC = """
            package fixtures.plugin.gamma;
            import java.util.TreeSet;
            import org.springframework.stereotype.Controller;
            import org.springframework.ui.Model;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.ResponseBody;
            @Controller
            public class GammaBare {
                @GetMapping("/__app/gamma/bare")
                @ResponseBody
                public String bare(Model model) { return String.join(",", new TreeSet<>(model.asMap().keySet())); }
            }
            """;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-k3-event-tier");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.build(external.resolve("plugins"), "gamma", """
                    id: gamma
                    version: 1.0.0
                    requiresSpi: ">=0.6.0"
                    mainClass: fixtures.plugin.gamma.GammaConfig
                    """, Map.of("fixtures.plugin.gamma.GammaConfig", CONFIG_SRC,
                    "fixtures.plugin.gamma.GammaSource", SOURCE_SRC,
                    "fixtures.plugin.gamma.GammaListener", LISTENER_SRC,
                    "fixtures.plugin.gamma.GammaProbe", PROBE_SRC,
                    "fixtures.plugin.gamma.GammaScreen", SCREEN_SRC,
                    "fixtures.plugin.gamma.GammaBare", BARE_SRC));
        }
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        Files.createDirectories(layout.siteDir("acme").resolve("templates"));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Event-Tier-Secret-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "",
                true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private OperatorShellModel shellModel;

    @SuppressWarnings("unchecked")
    private List<Object> seen() throws Exception {
        return (List<Object>) context.getBean("gammaListener").getClass().getField("SEEN").get(null);
    }

    private Object probe(String method, Object... args) throws Exception {
        Object probe = context.getBean("gammaProbe");
        Class<?>[] types = new Class<?>[args.length];
        java.util.Arrays.fill(types, String.class);
        Method m = probe.getClass().getMethod(method, types);
        try {
            return m.invoke(probe, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @BeforeEach
    void clear() throws Exception {
        if (PluginFixture.compilerAvailable()) {
            seen().clear();
        }
    }

    @Test
    void contentChangesAcceptsTheOwnSourceAndTheChangeComesBackAttributed() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        probe("announce", "acme", "gamma", "one");
        CatalogChange change = (CatalogChange) seen().get(seen().size() - 1);
        assertEquals(CatalogChange.Cause.CONTENT, change.cause());
        assertEquals("gamma", change.sourceKey());
        assertEquals("one", change.id());
        assertEquals("gamma", change.pluginId(), "the plugin that owns the announcing source");
        assertEquals("/gamma/one", change.currentUrls().get(0).path());
        assertTrue(change.local());
    }

    @Test
    void contentChangesRefusesASourceThePluginDoesNotOwn() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Exception refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> probe("announce", "acme", "pages", "home"));
        assertTrue(refused.getMessage().contains("kernel source"), refused.getMessage());
        assertTrue(seen().isEmpty(), "nothing was announced");
    }

    @Test
    void theCatalogViewListsThePluginsOwnContent() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        assertTrue(((List<?>) probe("composites", "acme")).contains("gamma:one"));
    }

    @Test
    void settingsChangesReachTheOwnPluginsListenerOnly() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        events.publishEvent(new SitePluginConfigChangedEvent("acme", "someone-else"));
        assertEquals(1, seen().size(), "another plugin's save: the catalog callback only");
        assertEquals(CatalogChange.Cause.SETTINGS, ((CatalogChange) seen().get(0)).cause());
        assertEquals("someone-else", ((CatalogChange) seen().get(0)).pluginId());
        seen().clear();

        events.publishEvent(new SitePluginConfigChangedEvent("acme", "gamma"));
        assertEquals(2, seen().size());
        SettingsChange own = (SettingsChange) seen().get(0);
        assertEquals("gamma", own.pluginId());
        assertTrue(seen().get(1) instanceof CatalogChange, "settings before catalog");
    }

    @Test
    void aConsoleScreenGetsTheKernelShellModelAndAnUnannotatedControllerDoesNot() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity())
                .build();
        String annotated = mvc.perform(get("/__app/gamma").with(user("root").roles(Roles.SYSTEM_SUPERADMIN)))
                .andReturn().getResponse().getContentAsString();
        String bare = mvc.perform(get("/__app/gamma/bare").with(user("root").roles(Roles.SYSTEM_SUPERADMIN)))
                .andReturn().getResponse().getContentAsString();

        // What a kernel screen gets for the same request: OperatorShellModel.populate is the one source.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__app/gamma");
        ExtendedModelMap kernel = new ExtendedModelMap();
        shellModel.populate(kernel, request, new UsernamePasswordAuthenticationToken("root", null, List.of()));
        Set<String> annotatedKeys = new TreeSet<>(List.of(annotated.split(",")));
        Set<String> bareKeys = new TreeSet<>(bare.isEmpty() ? List.of() : List.of(bare.split(",")));
        assertTrue(annotatedKeys.contains("topbarFragment") && annotatedKeys.contains("tenantSiteId"), annotated);
        assertTrue(!bareKeys.contains("topbarFragment"), "no shell model without @ConsoleScreen: " + bare);
        annotatedKeys.removeAll(bareKeys); // whatever global advice adds to every handler
        assertEquals(new TreeSet<>(kernel.asMap().keySet()), annotatedKeys, "model parity with a kernel screen");
    }
}
