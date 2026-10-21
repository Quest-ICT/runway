package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import mx.quest.runway.page.PageAuthoringValidator;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The tenant page-editor live preview: it renders a saved DRAFT (published() bypassed) and unsaved
 * editor content (via a per-request scratch page that is cleaned up), forces noindex + no-store, is gated by
 * EDIT_PAGES, and is bound to the caller's own tenancy only.
 */
@SpringBootTest
class PagePreviewScreenTest {

    private static final String PW = "Preview-Screen-7";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-e2");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));

        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));         // wildcard → passes EDIT_PAGES
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));       // plain sub-user → no EDIT_PAGES
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        // A renderable master + a DRAFT "home" page that pulls the master's chrome.
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html><html><head><title>Master</title></head>
                <body><header id="site-header"><span id="brand">PH</span></header>
                <main id="content">m</main><footer id="site-footer">Foot</footer></body></html>""");
        write(templates.resolve("_master.th.xml"), """
                <?xml version="1.0"?>
                <thlogic><attr sel="#brand" th:text="${site.displayName()}"/></thlogic>""");
        write(templates.resolve("home.html"), """
                <!DOCTYPE html><html><head><title>Home</title></head>
                <body><header id="site-header">PH</header>
                <main id="content"><h1 id="hometitle">PH</h1></main>
                <footer id="site-footer">PH</footer></body></html>""");
        write(templates.resolve("home.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#site-footer" th:replace="~{${master} :: #site-footer}"/>
                  <attr sel="#hometitle" th:text="${pageTitle}"/>
                </thlogic>""");
        write(templates.resolve("home.meta.yml"), "path: /\ntemplate: _master\ndraft: true\ntitle:\n  en: Home Page\n");

        // A DEEP-path page (published at /help/guide) whose content uses a RELATIVE asset URL — the exact shape
        // that breaks in the preview (served at /__admin/pages/guide/preview) without a public-path <base href>.
        write(templates.resolve("guide.html"), """
                <!DOCTYPE html><html><head><title>Guide</title></head>
                <body><header id="site-header">PH</header>
                <main id="content"><h1 id="hometitle">PH</h1>
                <img id="guide-img" src="../static/img/hero.jpg" alt="hero"></main>
                <footer id="site-footer">PH</footer></body></html>""");
        write(templates.resolve("guide.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#site-footer" th:replace="~{${master} :: #site-footer}"/>
                  <attr sel="#hometitle" th:text="${pageTitle}"/>
                </thlogic>""");
        write(templates.resolve("guide.meta.yml"),
                "path: /help/guide\ntemplate: _master\ndraft: true\ntitle:\n  en: Guide Page\n");

        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void previewRendersASavedDraftWithMasterChromeAndNoindexHeaders() throws Exception {
        mvc.perform(get("/__admin/pages/home/preview").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Acme Corp")))  // master #brand bound (draft rendered)
                .andExpect(content().string(Matchers.containsString("Home Page")))  // ${pageTitle} bound
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                // The tenant chain's security-header baseline (SecurityHeaders.baseline()) also stamps COOP.
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                // Author HTML is sandboxed (opaque origin) so its scripts can't reach the console session.
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-scripts"))
                // The kernel CSP filter must DEFER to the preview's own CSP — no second (report-only) header, which
                // would flood /__csp-report with the preview's sandboxed, un-nonced scripts.
                .andExpect(header().doesNotExist("Content-Security-Policy-Report-Only"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void previewOfUnsavedContentRendersItAndCleansUpTheScratch() throws Exception {
        String body = "<!DOCTYPE html><html><head></head><body><main id=\"content\">SCRATCH_MARKER_XYZ</main></body></html>";
        mvc.perform(post("/__admin/pages/home/preview").session(sessionFor("admin")).with(csrf())
                        .param("html", body).param("thxml", "<thlogic></thlogic>"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("SCRATCH_MARKER_XYZ")));

        Path templates = external.resolve("sites").resolve("acme").resolve("templates");
        try (Stream<Path> entries = Files.list(templates)) {
            assertTrue(entries.map(p -> p.getFileName().toString())
                            .noneMatch(n -> n.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)),
                    "the per-request preview scratch page is cleaned up");
        }
    }

    @Test
    void previewPinsABaseHrefToThePagesPublicPathSoRelativeUrlsResolveLikeLive() throws Exception {
        String html = mvc.perform(get("/__admin/pages/guide/preview").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The page publishes at /help/guide but the preview is served at /__admin/pages/guide/preview. Without a
        // <base>, "../static/img/hero.jpg" would resolve against that deep URL (→ /__admin/pages/static/… → 404);
        // the injected public-path <base> makes it resolve to /static/img/hero.jpg exactly as it does live.
        assertTrue(html.contains("<base href=\"/help/guide\">"),
                "preview injects a <base href> pinned to the page's public path; got:\n" + html);
        // It sits inside <head>, ahead of the content's relative <img>, so the browser applies it first.
        int head = html.indexOf("<head");
        int base = html.indexOf("<base href=");
        int img = html.indexOf("../static/img/hero.jpg");
        assertTrue(head >= 0 && head < base && base < img,
                "the <base> is inside <head> and precedes the relative image (head=" + head + " base=" + base
                        + " img=" + img + ")");
        // The author's relative URL is left verbatim — resolution is corrected via <base>, URLs are never rewritten.
        assertTrue(html.contains("src=\"../static/img/hero.jpg\""), "the relative URL is preserved as-authored");
    }

    /**
     * What the editor's live preview relies on when it navigates a sandboxed frame to the POST response: the
     * response carries its OWN sandbox CSP (and no second, report-only kernel header), is never cached or indexed,
     * and pins the public-path base so relative URLs resolve like the saved preview's.
     */
    @Test
    void anUnsavedPreviewCarriesTheSameIsolationHeadersAsTheSavedOne() throws Exception {
        mvc.perform(post("/__admin/pages/guide/preview").session(sessionFor("admin")).with(csrf())
                        .param("html", "<!DOCTYPE html><html><head></head><body><img src=\"../x.png\">LIVE</body></html>"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-scripts"))
                .andExpect(header().doesNotExist("Content-Security-Policy-Report-Only"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(Matchers.containsString("<base href=\"/help/guide\">")))
                .andExpect(content().string(Matchers.containsString("LIVE")));
    }

    @Test
    void anUnsavedPreviewNeedsCsrfAndEditPages() throws Exception {
        mvc.perform(post("/__admin/pages/home/preview").session(sessionFor("admin")).param("html", "x"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__admin/pages/home/preview").session(sessionFor("op")).with(csrf()).param("html", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorWithoutEditPagesIsForbidden() throws Exception {
        mvc.perform(get("/__admin/pages/home/preview").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSlugNotInTheBoundTenancyIsNotFound() throws Exception {
        mvc.perform(get("/__admin/pages/ghost/preview").session(sessionFor("admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePopOutWindowIsAClearlyLabelledScriptlessHostFramingTheSandboxedPreview() throws Exception {
        mvc.perform(get("/__admin/pages/home/preview-window").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Acme Corp")))                  // site name in the title/banner
                .andExpect(content().string(Matchers.containsString("/__admin/pages/home/preview"))) // frames the real preview
                .andExpect(content().string(Matchers.containsString("sandbox=\"allow-scripts\"")))    // the framed content stays sandboxed
                // the host itself runs no scripts (only the framed author content does, in its opaque origin)
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("script-src 'none'")));
    }

    @Test
    void thePopOutWindowRequiresEditPages() throws Exception {
        mvc.perform(get("/__admin/pages/home/preview-window").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
