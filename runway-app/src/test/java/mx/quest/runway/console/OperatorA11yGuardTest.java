package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import mx.quest.runway.backup.BackupService;
import mx.quest.runway.document.DocumentService;
import mx.quest.runway.i18n.SiteMessagesStore;
import mx.quest.runway.page.FragmentTemplates;
import mx.quest.runway.page.PageReview;
import mx.quest.runway.recovery.PasswordRecoveryService;
import mx.quest.runway.recovery.RecoveryToken;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.EditorialLevel;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.site.TenantSeeder;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * The accessibility regression guard: EVERY operator screen of both realms, plus the six top-level pages a signed-out
 * operator sees (both sign-ins, both recovery pages in both states, the restart page), is rendered as an
 * authorized user over fixture data that makes the iterated rows appear (two site languages, pages, a pending
 * revision and a submitted draft, a fragment, a document, backups, two app users, plugin settings of every type,
 * plugin menu entries in every topbar region) and then held to {@link OperatorA11yAudit} — in English AND in
 * Spanish, so {@code <html lang>} is checked against the operator locale actually in force.
 *
 * <p>"Every screen" is enforced, not hoped for: {@link #everyConsoleGetRouteIsAudited} walks the registered
 * {@code GET} handler mappings under {@code /__app} and {@code /__admin} and fails when one is neither audited
 * here nor listed as a non-HTML/non-screen exclusion. A new screen therefore cannot ship unaudited.
 */
@SpringBootTest
class OperatorA11yGuardTest {

    private static final String PW = "A11y-Guard-Secret-9";
    private static final String TOKEN = "a11y-guard-token";

    /** App-realm screens (audited as the platform superadmin). Patterns double as the route-coverage list. */
    private static final List<String[]> APP_SCREENS = List.of(
            new String[] {"/__app", "/__app"},
            new String[] {"/__app/sites", "/__app/sites"},
            new String[] {"/__app/backups", "/__app/backups"},
            new String[] {"/__app/site-templates", "/__app/site-templates"},
            new String[] {"/__app/site-templates/{templateId}", "/__app/site-templates/default"},
            new String[] {"/__app/site-templates/{templateId}/edit",
                    "/__app/site-templates/default/edit?name=_master.html"},
            new String[] {"/__app/masters", "/__app/masters"},
            new String[] {"/__app/users", "/__app/users"},
            new String[] {"/__app/permissions", "/__app/permissions"},
            new String[] {"/__app/bandwidth", "/__app/bandwidth"},
            new String[] {"/__app/logs", "/__app/logs"},
            new String[] {"/__app/plugins", "/__app/plugins"},
            new String[] {"/__app/plugins/{id}/settings", "/__app/plugins/demo/settings"},
            new String[] {"/__app/config/{screen}", "/__app/config/server"},
            new String[] {"/__app/embed-hosts", "/__app/embed-hosts"},
            new String[] {"/__app/logs/levels", "/__app/logs/levels"},
            new String[] {"/__app/settings", "/__app/settings"},
            new String[] {"/__app/about", "/__app/about"},
            new String[] {"/__app/account", "/__app/account"});

    /** Tenant-realm screens (audited as the site's admin). */
    private static final List<String[]> TENANT_SCREENS = List.of(
            new String[] {"/__admin", "/__admin"},
            new String[] {"/__admin/pages", "/__admin/pages"},
            new String[] {"/__admin/pages/new", "/__admin/pages/new"},
            new String[] {"/__admin/pages/{slug}/edit", "/__admin/pages/index/edit"},
            new String[] {"/__admin/review", "/__admin/review"},
            new String[] {"/__admin/review/{slug}", "/__admin/review/about"},
            new String[] {"/__admin/fragments", "/__admin/fragments"},
            new String[] {"/__admin/fragments/new", "/__admin/fragments/new"},
            new String[] {"/__admin/fragments/{name}/edit", "/__admin/fragments/_note/edit"},
            new String[] {"/__admin/widgets", "/__admin/widgets"},
            new String[] {"/__admin/documents", "/__admin/documents"},
            new String[] {"/__admin/static", "/__admin/static"},
            new String[] {"/__admin/messages", "/__admin/messages"},
            new String[] {"/__admin/users", "/__admin/users"},
            new String[] {"/__admin/permissions", "/__admin/permissions"},
            new String[] {"/__admin/insights", "/__admin/insights"},
            new String[] {"/__admin/bandwidth", "/__admin/bandwidth"},
            new String[] {"/__admin/logs", "/__admin/logs"},
            new String[] {"/__admin/config", "/__admin/config"},
            new String[] {"/__admin/seo", "/__admin/seo"},
            new String[] {"/__admin/icons", "/__admin/icons"},
            new String[] {"/__admin/manifest", "/__admin/manifest"},
            new String[] {"/__admin/backups", "/__admin/backups"},
            new String[] {"/__admin/maintenance", "/__admin/maintenance"},
            new String[] {"/__admin/plugins/{id}/settings", "/__admin/plugins/demo/settings"},
            new String[] {"/__admin/account", "/__admin/account"});

    /**
     * Console GET routes that are not HTML screens (downloads, exports, the public-page preview, which renders the
     * SITE's markup rather than the console's). Each must stay a deliberate exception.
     */
    private static final Set<String> NOT_SCREENS = Set.of(
            "/__app/site-templates/{templateId}/export",
            "/__app/backups/{siteId}/download",
            "/__app/backups/{siteId}/download-transfer",
            "/__app/logs/export.csv",
            "/__admin/backups/download",
            "/__admin/backups/download-transfer",
            "/__admin/backups/{name}/download",
            "/__admin/logs/audit.csv",
            "/__admin/pages/{slug}/preview",
            "/__admin/pages/{slug}/preview-window");

    /** The top-level pages a signed-out operator sees (both sign-ins, both recovery pages in both states). */
    private static final List<String> SIGNED_OUT = List.of(
            "/app/login", "/app/login?error&locked&captcha&logout&restarted&reset",
            "/login", "/login?error&locked&logout",
            "/app/forgot-password", "/forgot-password",
            "/app/reset?token=" + TOKEN, "/app/reset?token=unknown",
            "/reset?token=" + TOKEN, "/reset?token=unknown");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-a11y-guard");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), null, SiteStatus.ACTIVE, false, null,
                EditorialLevel.REVIEWED));
        UserStore users = new UserStore(yaml);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        Path appFile = external.resolve("app-users.yml");
        users.save(appFile, new User("app-master", "root", hash, "Root", "root@example.test", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(appFile, new User("op-id", "op", hash, "Op", "op@example.test", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "admin@example.test", true, false,
                Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        users.save(siteUsers, new User("acme-editor", "editor", hash, "Editor", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        new GrantStore(layout, yaml).saveSiteGrants("acme",
                GrantFile.empty().withUserPermission("acme-editor", "EDIT_PAGES"));

        // A plugin exercising every settings type and presentation key in BOTH realms' forms, and contributing a
        // menu entry to every topbar region of both realms (twice, so iterated entries must keep ids unique).
        Path demo = Files.createDirectories(external.resolve("plugins").resolve("demo"));
        Files.writeString(demo.resolve("plugin.yml"), """
                id: demo
                name: Demo
                version: 1.0.0
                settingGroups:
                  - { id: main, label: demo.g.main, help: demo.g.main.help }
                settings:
                  - { key: greeting, type: string, default: hi }
                  - { key: max, type: int, range: 1..100 }
                  - { key: enabled, type: boolean, group: main, help: demo.h.enabled }
                  - { key: enabled.detail, type: string, group: main, showWhen: enabled }
                  - { key: token, type: secret }
                  - { key: policy, type: enum, default: allow, range: "allow,disallow", group: main }
                  - { key: notes, type: text, advanced: true }
                  - { key: hosts, type: list, default: "a,b" }
                siteSettings:
                  - { key: site-greeting, type: string }
                  - { key: site-policy, type: enum, default: inherit, range: "inherit,allow,disallow", inheritsFrom: "app:policy" }
                  - { key: site-ping, type: boolean, default: false, requiresPlatform: enabled }
                  - { key: site-notes, type: text }
                menu:
                  - { realm: application, region: menu, order: 10, label: Demo one, path: /__app/demo-one, icon: fa-solid fa-flask }
                  - { realm: application, region: menu, order: 11, label: Demo two, path: /__app/demo-two, icon: fa-solid fa-flask }
                  - { realm: application, region: settings, order: 10, label: Demo set one, path: /__app/demo-s1 }
                  - { realm: application, region: settings, order: 11, label: Demo set two, path: /__app/demo-s2 }
                  - { realm: application, region: activity, order: 10, label: Demo act one, path: /__app/demo-a1 }
                  - { realm: application, region: activity, order: 11, label: Demo act two, path: /__app/demo-a2 }
                  - { realm: tenant, region: menu, order: 10, label: Demo one, path: /__admin/demo-one, icon: fa-solid fa-flask }
                  - { realm: tenant, region: menu, order: 11, label: Demo two, path: /__admin/demo-two, icon: fa-solid fa-flask }
                  - { realm: tenant, region: settings, order: 10, label: Demo set one, path: /__admin/demo-s1 }
                  - { realm: tenant, region: settings, order: 11, label: Demo set two, path: /__admin/demo-s2 }
                  - { realm: tenant, region: activity, order: 10, label: Demo act one, path: /__admin/demo-a1 }
                  - { realm: tenant, region: activity, order: 11, label: Demo act two, path: /__admin/demo-a2 }
                """);
        Path second = Files.createDirectories(external.resolve("plugins").resolve("second"));
        Files.writeString(second.resolve("plugin.yml"), """
                id: second
                name: Second
                version: 1.0.0
                siteSettings:
                  - { key: x, type: string }
                """);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.storage.backups", () -> external.resolve("backups").toString()); // not the module dir
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.security.secret-key", () -> "a-test-master-key-please-change-32b");
        // Server settings with fields the server sets, so the locked rows render: one offering "Use the server's
        // value" (and the page its adopt form), one equal to the default (no button).
        registry.add("runway.csp.report-uri", () -> "/a11y-csp-report");
        registry.add("runway.csp.mode", () -> "report");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private TenantSeeder seeder;
    @Autowired
    private FragmentTemplates fragments;
    @Autowired
    private DocumentService documents;
    @Autowired
    private BackupService backups;
    @Autowired
    private SiteMessagesStore siteMessages;
    @Autowired
    private StorageLayout layout;
    @Autowired
    private SpringTemplateEngine templateEngine;
    @Autowired
    private AssetVersion assetVersion;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @MockitoSpyBean
    private PasswordRecoveryService recovery;
    private MockMvc mvc;
    private static boolean seeded;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
        // Recovery is offered (so sign-in shows its link) and a known token is valid in both realms, so the reset
        // page renders its FORM state, not only the "link expired" one.
        doReturn(true).when(recovery).isAvailable();
        doReturn(Optional.of(new RecoveryToken("h", Realm.APPLICATION, null, "app-master", "root",
                Long.MAX_VALUE))).when(recovery).validateApp(TOKEN);
        doReturn(Optional.of(new RecoveryToken("h", Realm.TENANT, "acme", "acme-admin", "admin",
                Long.MAX_VALUE))).when(recovery).validateTenant(eq(TOKEN), any());
        if (!seeded) {
            seed();
            seeded = true;
        }
    }

    /** Fixture rows, created once through the real services so every list renders its iterated markup. */
    private void seed() throws Exception {
        seeder.seed("acme");
        fragments.create("acme", "_note", "<div class=\"note\">A note</div>", null);
        documents.add("acme", "guide.pdf", "%PDF-1.4 a11y".getBytes(StandardCharsets.UTF_8));
        backups.create("acme");
        backups.create("acme");
        List<Locale> locales = List.of(Locale.ENGLISH, Locale.forLanguageTag("es"));
        siteMessages.save("acme", locales, java.util.Map.of(
                "home.title", java.util.Map.of("en", "Home", "es", "Inicio"),
                "home.lead", java.util.Map.of("en", "Welcome", "es", "Bienvenido")));
        // A submitted draft (queue row without a revision) ...
        Path pending = layout.siteDir("acme").resolve("templates").resolve(".pending").resolve("about");
        Files.createDirectories(pending);
        new YamlStore().write(pending.resolve("review.yml"),
                PageReview.submitted("acme-editor", Instant.parse("2026-10-01T00:00:00Z")));
        // ... and a pending revision of a live page, made the real way: an editor without approval saves it.
        MockHttpSession editor = tenantLogin("editor");
        mvc.perform(post("/__admin/pages/index").session(editor).with(csrf())
                .param("html", "<!DOCTYPE html><html><head><title>t</title></head><body><main><h1>H</h1>"
                        + "<p>Proposed</p></main></body></html>")
                .param("path", "/").param("template", "_master").param("title[en]", "Home v2"));
    }

    private MockHttpSession appLogin(String user) throws Exception {
        MvcResult r = mvc.perform(formLogin("/app/login").user(user).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MockHttpSession tenantLogin(String user) throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user(user).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    /** GETs {@code url} in {@code lang}, expecting a 200 HTML page; returns its body. */
    private String render(String url, MockHttpSession session, String lang, List<String> problems)
            throws Exception {
        var request = get(url).cookie(new Cookie(OperatorLocaleResolver.COOKIE, lang));
        if (session != null) {
            request = request.session(session);
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        if (response.getStatus() != 200) {
            problems.add(url + " [" + lang + "]: HTTP " + response.getStatus() + " (expected a rendered screen)");
            return null;
        }
        response.setCharacterEncoding("UTF-8");
        return response.getContentAsString();
    }

    private void auditPage(String url, String html, String lang, List<String> problems) {
        if (html == null) {
            return;
        }
        String where = url + " [" + lang + "]";
        problems.addAll(OperatorA11yAudit.audit(where, html));
        Document doc = Jsoup.parse(html);
        String declared = doc.selectFirst("html").attr("lang");
        if (!lang.equals(declared)) {
            problems.add(where + ": <html lang=\"" + declared + "\"> does not follow the operator locale");
        }
    }

    private static void assertClean(List<String> problems) {
        assertTrue(problems.isEmpty(), problems.size() + " accessibility violation(s):\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void everyAppRealmScreenPassesTheAudit() throws Exception {
        List<String> problems = new ArrayList<>();
        MockHttpSession root = appLogin("root");
        for (String lang : List.of("en", "es")) {
            for (String[] screen : APP_SCREENS) {
                auditPage(screen[1], render(screen[1], root, lang, problems), lang, problems);
            }
        }
        assertClean(problems);
    }

    @Test
    void everyTenantRealmScreenPassesTheAudit() throws Exception {
        List<String> problems = new ArrayList<>();
        MockHttpSession admin = tenantLogin("admin");
        for (String lang : List.of("en", "es")) {
            for (String[] screen : TENANT_SCREENS) {
                auditPage(screen[1], render(screen[1], admin, lang, problems), lang, problems);
            }
        }
        assertClean(problems);
    }

    @Test
    void everySignedOutPagePassesTheAudit() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String lang : List.of("en", "es")) {
            for (String url : SIGNED_OUT) {
                auditPage(url, render(url, null, lang, problems), lang, problems);
            }
            // The restart page is the response to a plugin change that ends the JVM; render the template itself.
            Context ctx = new Context(Locale.forLanguageTag(lang));
            ctx.setVariable("cspNonce", "");
            auditPage("operator/restarting", templateEngine.process("operator/restarting", ctx), lang, problems);
        }
        assertClean(problems);
    }

    /**
     * The cache-busting guard (console assets are versioned): on every screen above, the
     * signed-out pages, the restart page and the detached preview window, each {@code /__assets/...} reference
     * carries {@code ?v=<the AssetVersion token>}. A versioned asset is cached for a day, so a reference without the
     * token is what let a browser keep running the previous release's {@code page-editor.js} after an upgrade.
     * (The server's {@code no-cache} answer to an unversioned request is the safety net, not the plan.)
     */
    @Test
    void everyAssetReferenceIsVersioned() throws Exception {
        String token = assetVersion.value();
        List<String> problems = new ArrayList<>();
        List<String> pages = new ArrayList<>();
        MockHttpSession root = appLogin("root");
        for (String[] screen : APP_SCREENS) {
            pages.add(screen[1]);
            pages.add(render(screen[1], root, "en", problems));
        }
        MockHttpSession admin = tenantLogin("admin");
        for (String[] screen : TENANT_SCREENS) {
            pages.add(screen[1]);
            pages.add(render(screen[1], admin, "en", problems));
        }
        // The detached preview window is hand-built HTML (TenantPagePreviewController), not a template.
        pages.add("/__admin/pages/index/preview-window");
        pages.add(render("/__admin/pages/index/preview-window", admin, "en", problems));
        for (String url : SIGNED_OUT) {
            pages.add(url);
            pages.add(render(url, null, "en", problems));
        }
        // The restart page: rendered by PluginAdminController (console package), whose advice supplies the token.
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("cspNonce", "");
        ctx.setVariable("assetVersion", token);
        pages.add("operator/restarting");
        pages.add(templateEngine.process("operator/restarting", ctx));

        for (int i = 0; i < pages.size(); i += 2) {
            String html = pages.get(i + 1);
            if (html != null) {
                problems.addAll(AssetVersionAudit.audit(pages.get(i), html, token));
                // Guards the guard: a page with no reference at all was not really audited.
                if (AssetVersionAudit.count(html) == 0) {
                    problems.add(pages.get(i) + ": no /__assets reference at all (did the page really render?)");
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " unversioned /__assets reference(s):\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void theScriptsThatWentStaleAreVersionedWithTheSameTokenAsTheShell() throws Exception {
        String token = assetVersion.value();
        List<String> none = new ArrayList<>();
        MockHttpSession admin = tenantLogin("admin");
        Document editor = Jsoup.parse(render("/__admin/pages/index/edit", admin, "en", none));
        assertEquals(List.of("/__assets/page-editor.js?v=" + token),
                editor.select("script[src^=/__assets/page-editor.js]").eachAttr("src"),
                "the page editor's own script (the one observed stale) carries the token");
        assertEquals(List.of("/__assets/vendor/codemirror.min.js?v=" + token),
                editor.select("script[src^=/__assets/vendor/codemirror]").eachAttr("src"), "vendor bundles too");
        Document messages = Jsoup.parse(render("/__admin/messages", admin, "en", none));
        assertEquals(List.of("/__assets/messages.js?v=" + token),
                messages.select("script[src^=/__assets/messages.js]").eachAttr("src"));
        Document login = Jsoup.parse(render("/login", null, "en", none));
        assertEquals(List.of("/__assets/captcha-v3.js?v=" + token),
                login.select("script[src^=/__assets/captcha-v3.js]").eachAttr("src"));
        assertTrue(none.isEmpty(), String.join("\n", none));
    }

    @Test
    void theActiveMenuEntryAndTheCurrentLanguageAreAnnouncedNotOnlyColoured() throws Exception {
        List<String> none = new ArrayList<>();
        Document app = Jsoup.parse(render("/__app/users", appLogin("root"), "es", none));
        assertEquals("es", app.selectFirst("html").attr("lang"));
        assertEquals("Saltar al contenido principal", app.selectFirst("a.rw-skip-link").text());
        assertEquals("#op-main", app.selectFirst("a.rw-skip-link").attr("href"));
        assertEquals("Menú de la consola", app.selectFirst("nav#navbar-menu").attr("aria-label"));
        assertEquals(List.of("/__app/users"), app.select("#navbar-menu [aria-current=page]").eachAttr("href"),
                "exactly the active entry is aria-current=page");
        assertEquals(1, app.select(".rw-topbar .dropdown-item[aria-current=true]").size(), "one current language");
        assertTrue(app.selectFirst(".rw-topbar .dropdown-item[aria-current=true]").attr("href").contains("lang=es"));
        assertEquals("Mostrar / ocultar contraseña", app.body().attr("data-i18n-pw-toggle"));
        assertEquals("Opciones de la columna", app.body().attr("data-lt-menu"), "listing-table strings localized");
        assertEquals("Intro ordena por esta columna. Flecha abajo o Mayús+F10 abre las opciones de la columna.",
                app.body().attr("data-lt-key-hint"));

        Document tenant = Jsoup.parse(render("/__admin/pages", tenantLogin("admin"), "en", none));
        assertEquals(List.of("/__admin/pages"), tenant.select("#navbar-menu [aria-current=page]").eachAttr("href"));
        Document dashboard = Jsoup.parse(render("/__admin", tenantLogin("admin"), "en", none));
        assertEquals(List.of("/__admin"), dashboard.select("#navbar-menu [aria-current=page]").eachAttr("href"));
        Document account = Jsoup.parse(render("/__admin/account", tenantLogin("admin"), "en", none));
        assertEquals(0, account.select("#navbar-menu [aria-current]").size(), "no menu entry is current here");

        Document login = Jsoup.parse(render("/app/login", null, "es", none));
        assertEquals("es", login.selectFirst("html").attr("lang"));
        assertEquals(List.of("Español"), login.select("#lg-langswitch a[aria-current=true]").eachText());
        assertTrue(none.isEmpty(), String.join("\n", none));
    }

    @Test
    void aLockedOutSignInSaysWhyOnBothRealms() throws Exception {
        List<String> none = new ArrayList<>();
        Document app = Jsoup.parse(render("/app/login?locked", null, "en", none));
        assertEquals("Too many failed sign-in attempts from your network. Wait a few minutes, then try again.",
                app.select("#lg-locked").text());
        Document site = Jsoup.parse(render("/login?locked", null, "es", none));
        assertTrue(site.select("#lg-locked").text().startsWith("Demasiados intentos fallidos"), site.select("#lg-locked").text());
        // Without the flag the alert is gone.
        assertEquals(0, Jsoup.parse(render("/login", null, "en", none)).select("#lg-locked").size());
        assertTrue(none.isEmpty(), String.join("\n", none));
    }

    @Test
    void theAboutKeyValueTablesUseRowHeaders() throws Exception {
        Document about = Jsoup.parse(render("/__app/about", appLogin("root"), "en", new ArrayList<>()));
        assertTrue(about.select("tbody th[scope=row]").size() > 10, "each setting name is a row header");
    }

    @Test
    void theFixtureReallyRendersTheIteratedMarkup() throws Exception {
        // Guards the guard: an audit over empty lists would pass vacuously. Each marker is a row the fixture made.
        MockHttpSession admin = tenantLogin("admin");
        List<String> none = new ArrayList<>();
        assertTrue(render("/__admin/review", admin, "en", none).contains("/__admin/review/index"),
                "the pending revision is queued");
        assertTrue(render("/__admin/review", admin, "en", none).contains("/__admin/review/about"),
                "the submitted draft is queued");
        assertTrue(render("/__admin/documents", admin, "en", none).contains("guide.pdf"));
        assertTrue(render("/__admin/messages", admin, "en", none).contains("home.lead"), "two message rows");
        assertTrue(render("/__admin/fragments", admin, "en", none).contains("/__admin/fragments/_note/edit"));
        assertTrue(render("/__admin/plugins/demo/settings", admin, "en", none).contains("site-greeting"));
        String app = render("/__app/plugins/demo/settings", appLogin("root"), "en", none);
        assertTrue(app.contains("/__app/demo-s2") && app.contains("/__app/demo-a2"),
                "two plugin entries in each topbar region");
        String server = render("/__app/config/server", appLogin("root"), "en", none);
        assertTrue(server.contains("id=\"lk-runway.csp.report-uri\"") && server.contains("sr-adopt")
                && server.contains("id=\"st-adopt-form\""), "a locked row with its adopt button and form");
        assertTrue(none.isEmpty(), String.join("\n", none));
    }

    @Test
    void everyConsoleGetRouteIsAudited() {
        Set<String> audited = new TreeSet<>();
        APP_SCREENS.forEach(s -> audited.add(s[0]));
        TENANT_SCREENS.forEach(s -> audited.add(s[0]));
        Set<String> missing = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (!methods.isEmpty() && !methods.contains(RequestMethod.GET)) {
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                String p = pattern.endsWith("/") && pattern.length() > 1 ? pattern.substring(0, pattern.length() - 1)
                        : pattern;
                boolean console = p.equals("/__app") || p.startsWith("/__app/") || p.equals("/__admin")
                        || p.startsWith("/__admin/");
                if (console && !audited.contains(p) && !NOT_SCREENS.contains(p)) {
                    missing.add(p);
                }
            }
        }
        assertEquals(Set.of(), missing, "console GET routes that the accessibility guard does not render: add each "
                + "to APP_SCREENS/TENANT_SCREENS (or, if it is not an HTML screen, to NOT_SCREENS)");
    }
}
