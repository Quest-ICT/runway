package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;

/**
 * The operator master-as-shell, end to end: the app home renders through {@code op-layout} (the master pulls
 * the realm topbar + the content fragment by model-variable id-selectors, decoupled logic applies, operator
 * i18n resolves), the {@code /__assets} handler serves the self-hosted chrome, and the admin realm selects the
 * other topbar. App-realm is driven through the real security stack (so request-bound i18n routing is
 * exercised); the admin topbar is proven by a direct engine render bound to a {@code /__admin} request.
 */
@SpringBootTest
class OperatorShellTest {

    private static final String PASSWORD = "Sh3ll-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-9d");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SpringTemplateEngine templateEngine;
    @Autowired
    private ApplicationContext applicationContext;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user("admin").password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void appHomeRendersThroughTheShellWithOperatorI18n() throws Exception {
        String html = mvc.perform(get("/__app").session(login()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("rw-topbar-app")))
                // The app chain's security-header baseline: X-Frame-Options SAMEORIGIN + COOP same-origin.
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andReturn().getResponse().getContentAsString();

        // The master applied its logic: title composed, content fragment pulled, operator name bound.
        assertTrue(html.contains("Operator console · Runway"), "shell title composed from #{} + appName");
        assertTrue(html.contains("admin"), "the signed-in operator name is bound into the topbar/home");
        // Operator i18n resolved (NOT the raw key) — request-bound routing chose the operator bundle.
        assertTrue(html.contains(">Plugins<"), "nav label resolved via operator messages; got no 'Plugins'");
        assertFalse(html.contains("op.nav.plugins"), "the raw message key must not leak through");
        // Self-hosted chrome referenced (no CDN), with the cache-buster on Runway's own chrome.
        assertTrue(html.contains("/__assets/vendor/tabler.min.css"));
        assertTrue(html.contains("/__assets/vendor/htmx.min.js"));
        assertTrue(html.contains("/__assets/runway-chrome.css?v="));
        // The Runway logo (favicon + topbar brand) is the shipped runway.svg.
        assertTrue(html.contains("rel=\"icon\""), "favicon linked");
        assertTrue(html.contains("/__assets/runway.svg"), "runway.svg used for the favicon + brand logo");
        // Admin topbar must NOT appear on an app-realm page.
        assertFalse(html.contains("rw-topbar-admin"));
    }

    @Test
    void operatorI18nHonoursTheAcceptLanguageHeader() throws Exception {
        String html = mvc.perform(get("/__app").session(login()).header("Accept-Language", "es"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains(">Complementos<"), "Spanish operator bundle resolved (Plugins -> Complementos)");
    }

    @Test
    void selfHostedAssetsAreServedFromTheClasspath() throws Exception {
        mvc.perform(get("/__assets/vendor/htmx.min.js")).andExpect(status().isOk());
        mvc.perform(get("/__assets/runway-chrome.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("wirePasswordToggles")));
        mvc.perform(get("/__assets/vendor/fontawesome/css/all.min.css")).andExpect(status().isOk());
        mvc.perform(get("/__assets/listing-tables.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ListingTables")));
        mvc.perform(get("/__assets/listing-tables.css")).andExpect(status().isOk());
        mvc.perform(get("/__assets/runway.svg"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg")));
    }

    @Test
    void theAdminTopbarIsSelectedForTheTenantRealm() {
        // Bind a /__admin request so OperatorAwareMessageSource routes to operator i18n, then render the shell
        // directly (a real tenant session is exercised elsewhere; here we only prove topbar selection + i18n).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("contentTemplate", "operator/admin/home");
        ctx.setVariable("topbarFragment", "operator/fragments/admin-topbar");
        ctx.setVariable("pageTitleKey", "op.admin.home.title");
        ctx.setVariable("appName", "Runway");
        ctx.setVariable("principalName", "manager");
        ctx.setVariable("currentPath", "/__admin");
        ctx.setVariable("assetVersion", "1");
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, DefaultConversionService.getSharedInstance()));

        String html = templateEngine.process("operator/op-layout", ctx);
        assertTrue(html.contains("rw-topbar-admin"), "tenant realm selects the admin topbar");
        assertFalse(html.contains("rw-topbar-app"));
        assertTrue(html.contains(">Permissions<"), "admin nav resolved via operator i18n");
        assertTrue(html.contains("Site administration"), "admin home content fragment pulled");
    }

    @Test
    void everyShellScriptCarriesTheCspNonceAndSrcScriptsKeepTheirCacheBustedSrc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__app");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("contentTemplate", "operator/app/home");
        ctx.setVariable("topbarFragment", "operator/fragments/app-topbar");
        ctx.setVariable("pageTitleKey", "op.app.home.title");
        ctx.setVariable("appName", "Runway");
        ctx.setVariable("assetVersion", "1");
        ctx.setVariable("cspNonce", "TESTNONCE123"); // the CspFilter supplies a fresh one per request in production
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, DefaultConversionService.getSharedInstance()));

        String html = templateEngine.process("operator/op-layout", ctx);

        long scripts = html.split("<script", -1).length - 1;
        long nonced = html.split("nonce=\"TESTNONCE123\"", -1).length - 1;
        assertTrue(scripts >= 5, "the 5 shell scripts (htmx, alpine, tabler, chrome, listing-tables) are present; got " + scripts);
        // The sel="script" element selector cannot miss a tag — every <script> carries the per-request nonce.
        assertEquals(scripts, nonced, "every <script> in the shell must carry the nonce (" + scripts + " scripts, " + nonced + " nonced)");
        // th:src (not th:attr) preserved the cache-busted src on the id-bearing scripts, so they didn't collide with the nonce.
        assertTrue(html.contains("src=\"/__assets/runway-chrome.js?v=1\""), "op-chrome-js keeps its ?v= src via th:src");
        assertTrue(html.contains("src=\"/__assets/listing-tables.js?v=1\""), "op-lt-js keeps its ?v= src via th:src");
    }
}
