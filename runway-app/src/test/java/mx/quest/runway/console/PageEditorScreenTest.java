package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
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
 * The page editor screen: renders the source + metadata + preview + advisory quality panel, saves
 * through {@link PageWriteService} (preserving the draft flag), rejects bad input with a flash error, and is
 * gated by EDIT_PAGES.
 */
@SpringBootTest
class PageEditorScreenTest {

    /** A content source claiming one fixture page's path — drives the shadowing advisory end-to-end. */
    @org.springframework.boot.test.context.TestConfiguration
    static class ShadowingSourceConfig {
        @org.springframework.context.annotation.Bean
        mx.quest.runway.spi.ContentSource shadowingSource() {
            return new mx.quest.runway.spi.ContentSource() {
                @Override public String key() { return "blog"; }
                @Override public java.util.List<mx.quest.runway.spi.model.ContentRef> enumerate(
                        mx.quest.runway.spi.model.SiteView site) {
                    return java.util.List.of(mx.quest.runway.spi.model.ContentRef.of("my-post", "/shadowed",
                            java.util.Map.of(), java.util.List.of(), java.util.Map.of("en", "My post"),
                            java.util.Map.of(), "__site__/" + site.id() + "/entry", null));
                }
                @Override public mx.quest.runway.spi.model.ContentBody body(
                        mx.quest.runway.spi.model.SiteView site, String id, java.util.Locale locale) {
                    return mx.quest.runway.spi.model.ContentBody.empty();
                }
            };
        }
    }

    private static final String PW = "Editor-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-e4");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), null, SiteStatus.ACTIVE));

        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html><html><head><title>Master</title></head>
                <body><header id="site-header"><span id="brand">PH</span></header>
                <main id="content">m</main><footer id="site-footer">Foot</footer></body></html>""");
        write(templates.resolve("_master.th.xml"), """
                <?xml version="1.0"?>
                <thlogic><attr sel="#brand" th:text="${site.displayName()}"/></thlogic>""");
        // A titled page (quality clean) that pulls the master.
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
        // Offered in en+es with no per-locale paths → triggers the i18n-URL advisory (asserted below).
        write(templates.resolve("home.meta.yml"),
                "path: /\ntemplate: _master\ndraft: true\nlocales: [en, es]\ntitle:\n  en: Home Page\n");
        // A dedicated page the mutating tests (save / reject) edit, so the read-only tests stay independent.
        write(templates.resolve("editme.html"), """
                <!DOCTYPE html><html><head><title>Edit me</title></head>
                <body><header id="site-header">PH</header>
                <main id="content"><h1 id="hometitle">PH</h1></main>
                <footer id="site-footer">PH</footer></body></html>""");
        write(templates.resolve("editme.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#site-footer" th:replace="~{${master} :: #site-footer}"/>
                  <attr sel="#hometitle" th:text="${pageTitle}"/>
                </thlogic>""");
        write(templates.resolve("editme.meta.yml"), "path: /editme\ntemplate: _master\ndraft: true\ntitle:\n  en: Edit Me\n");
        // A page at the exact path the ShadowingSourceConfig source claims — drives the shadowing advisory.
        write(templates.resolve("shadowed.html"), "<!DOCTYPE html><html><head><title>S</title></head><body>"
                + "<header id=\"site-header\">h</header><main id=\"content\">s</main></body></html>");
        write(templates.resolve("shadowed.th.xml"),
                "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
        write(templates.resolve("shadowed.meta.yml"),
                "path: /shadowed\ntemplate: _master\ntitle:\n  en: Shadowed\n");
        // A page carrying template parameters, to assert the params editor renders and round-trips.
        write(templates.resolve("paramed.html"), """
                <!DOCTYPE html><html><head><title>Paramed</title></head>
                <body><header id="site-header">PH</header>
                <main id="content"><h1 id="hometitle">PH</h1></main>
                <footer id="site-footer">PH</footer></body></html>""");
        write(templates.resolve("paramed.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#site-footer" th:replace="~{${master} :: #site-footer}"/>
                  <attr sel="#hometitle" th:text="${pageTitle}"/>
                </thlogic>""");
        write(templates.resolve("paramed.meta.yml"),
                "path: /paramed\ntemplate: _master\ndraft: true\ntitle:\n  en: Paramed\nparams:\n  analytics: UA-42\n");
        // Site-level params: 'analytics' is overridden by the paramed page; 'brand' is only inherited.
        write(layout.siteDir("acme").resolve("params.yml"), "analytics: UA-SITE\nbrand: Acme Blue\n");
        // A title-less bare page (quality flags title.missing).
        write(templates.resolve("untitled.html"),
                "<!DOCTYPE html><html><head><title></title></head><body><main id=\"content\">Untitled</main></body></html>");
        write(templates.resolve("untitled.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(templates.resolve("untitled.meta.yml"), "path: /untitled\ndraft: true\n");
        // A page whose draft render throws (division by zero in its logic) → an ERROR quality finding, used to
        // assert that error findings render with NO "ignore" control (errors are never ignorable).
        write(templates.resolve("broken.html"), """
                <!DOCTYPE html><html><head><title>Broken</title></head>
                <body><header id="site-header">PH</header>
                <main id="content"><span id="x">x</span></main>
                <footer id="site-footer">PH</footer></body></html>""");
        write(templates.resolve("broken.th.xml"), """
                <?xml version="1.0"?>
                <thlogic><attr sel="#x" th:text="${1/0}"/></thlogic>""");
        write(templates.resolve("broken.meta.yml"), "path: /broken\ntemplate: _master\ndraft: true\ntitle:\n  en: Broken\n");

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
    @Autowired
    private PageStore pages;
    @Autowired
    private PageWriteService writer;
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
    void theEditorRendersSourceMetadataPreviewAndTheI18nUrlAdvisory() throws Exception {
        mvc.perform(get("/__admin/pages/home/edit").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/__assets/vendor/codemirror.min.js"))) // scripts seam
                .andExpect(content().string(Matchers.containsString("hometitle")))            // the html source in the textarea (HTML-escaped)
                .andExpect(content().string(Matchers.containsString("/__admin/pages/home/preview"))) // preview iframe
                .andExpect(content().string(Matchers.containsString("sandbox=\"allow-scripts\""))) // iframe sandboxed
                .andExpect(content().string(Matchers.containsString("id=\"pe-popout\"")))      // pop-out preview control
                .andExpect(content().string(Matchers.containsString("data-label-back=\"Reattach\""))) // its toggle labels (localized)
                .andExpect(content().string(Matchers.containsString("/__admin/pages/home/preview-window"))) // pop-out opens the host window
                .andExpect(content().string(Matchers.containsString("id=\"pe-body-expand\"")))  // editor expand toggle
                .andExpect(content().string(Matchers.containsString("id=\"pe-body-host\"")))    // its capped-height host
                .andExpect(content().string(Matchers.containsString("Home Page")))            // per-locale title value
                // Download/upload controls on both source editors, with the page's own filenames.
                .andExpect(content().string(Matchers.containsString("pe-cm-download")))
                .andExpect(content().string(Matchers.containsString("pe-cm-upload")))
                .andExpect(content().string(Matchers.containsString("data-filename=\"home.html\"")))
                .andExpect(content().string(Matchers.containsString("data-filename=\"home.th.xml\"")))
                .andExpect(content().string(Matchers.containsString("data-ratio=\"34\"")))     // the ¾ column-split option
                .andExpect(content().string(Matchers.containsString("id=\"pe-quality-flag\"")))// preview status flag
                // multilingual site (en+es) + a page with no per-locale paths → the i18n-URL advisory fires,
                // carrying its stable code + severity (for the client ignore-set) and an ignore control (info).
                .andExpect(content().string(Matchers.containsString("URLs are not localized")))
                .andExpect(content().string(Matchers.containsString("data-code=\"i18nPath\"")))
                .andExpect(content().string(Matchers.containsString("data-severity=\"info\"")))
                .andExpect(content().string(Matchers.containsString("pe-f-ignore")));
    }

    @Test
    void anErrorFindingRendersWithoutAnIgnoreControlWhileAWarningKeepsIt() throws Exception {
        // The broken page's draft render throws → an ERROR finding, which must carry NO ignore control.
        String broken = mvc.perform(get("/__admin/pages/broken/edit").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(findingRow(broken, "error").contains("pe-f-ignore"), "an error finding has NO ignore control");

        // The untitled page also has WARNINGs (no description, no <h1>), which must keep their ignore control.
        String untitled = mvc.perform(get("/__admin/pages/untitled/edit").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(findingRow(untitled, "warning").contains("pe-f-ignore"), "a warning finding keeps its ignore control");
    }

    /** The inner HTML of the first quality-finding {@code <li>} of the given severity (fails if none). */
    private static String findingRow(String body, String severity) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<li[^>]*data-severity=\"" + severity + "\"[^>]*>(.*?)</li>", java.util.regex.Pattern.DOTALL)
                .matcher(body);
        assertTrue(m.find(), "a " + severity + " finding row is present");
        return m.group(1);
    }

    @Test
    void theEditorShowsAQualityFindingForATitlelessPage() throws Exception {
        mvc.perform(get("/__admin/pages/untitled/edit").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("The page has no title")));
    }

    @Test
    void theEditorWarnsWhenThePagePathShadowsPluginContent() throws Exception {
        // The visibility triple's editor leg: this page's path is also served by the fixture blog source —
        // the advisory must name the path and the composite claimant, where the author is looking.
        String html = mvc.perform(get("/__admin/pages/shadowed/edit").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("pathShadowsContent"), "the shadowing advisory finding is present");
        assertTrue(html.contains("blog:my-post"), "the shadowed content is named");
    }

    @Test
    void savingUpdatesTheContentAndMetadataAndPreservesTheDraftFlag() throws Exception {
        mvc.perform(post("/__admin/pages/editme").session(sessionFor("admin")).with(csrf())
                        .param("html", "<html><body>NEW_BODY_MARK</body></html>")
                        .param("thxml", "<thlogic></thlogic>")
                        .param("path", "/editme").param("template", "_master")
                        .param("title[en]", "New Title").param("locales", "en"))
                .andExpect(status().is3xxRedirection());

        assertEquals("<html><body>NEW_BODY_MARK</body></html>", writer.readBody("acme", "editme").orElseThrow());
        assertEquals("New Title", pages.find("acme", "editme").orElseThrow().title().get("en"));
        assertTrue(pages.find("acme", "editme").orElseThrow().draft(), "editing content keeps the draft state");
    }

    @Test
    void theEditorRendersTheTemplateParametersEditor() throws Exception {
        mvc.perform(get("/__admin/pages/paramed/edit").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Template parameters"))) // the params label
                .andExpect(content().string(Matchers.containsString("value=\"analytics\"")))  // the existing key
                .andExpect(content().string(Matchers.containsString("value=\"UA-42\"")));      // the existing value
    }

    @Test
    void eachPageParameterShowsTheSiteValueItOverridesAndTheRestAreListedAsInherited() throws Exception {
        String html = mvc.perform(get("/__admin/pages/paramed/edit").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Overrides the site value: UA-SITE"),
                "the page's analytics row shows the site value it overrides");
        assertTrue(html.contains("Inherited from the site"), "the inherited block renders");
        assertTrue(html.contains("Acme Blue"), "the site-only 'brand' param is listed as inherited");
        int inheritedAt = html.indexOf("pe-params-inherited");
        assertFalse(html.substring(inheritedAt).contains(">analytics<"),
                "an overridden key is NOT listed again as inherited");
    }

    @Test
    void aPageWithoutParametersListsEverySiteParameterAsInheritedAndNoOverrideHint() throws Exception {
        String html = mvc.perform(get("/__admin/pages/home/edit").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(html.contains("Overrides the site value"), "nothing is overridden");
        assertTrue(html.contains("UA-SITE") && html.contains("Acme Blue"), "both site params are shown read-only");
        assertFalse(html.contains("name=\"paramKey\" type=\"text\" value=\"brand\""),
                "inherited params are display-only, never posted back as page params");
    }

    @Test
    void savingThreadsTemplateParametersThroughToThePage() throws Exception {
        mvc.perform(post("/__admin/pages/editme").session(sessionFor("admin")).with(csrf())
                        .param("html", "<html><body>P</body></html>")
                        .param("path", "/editme").param("template", "_master")
                        .param("title[en]", "Edit Me").param("locales", "en")
                        // Two param rows plus a blank one (the editor's trailing add-row) — the blank drops.
                        .param("paramKey", "analytics", "region", "")
                        .param("paramValue", "UA-9", "eu", ""))
                .andExpect(status().is3xxRedirection());

        var params = pages.find("acme", "editme").orElseThrow().params();
        assertEquals("UA-9", params.get("analytics"));
        assertEquals("eu", params.get("region"));
        assertEquals(2, params.size(), "the trailing blank-key row is dropped");
    }

    @Test
    void savingAnUnsafePathIsRejectedAndTheBodyIsUnchanged() throws Exception {
        String before = writer.readBody("acme", "untitled").orElseThrow();
        mvc.perform(post("/__admin/pages/untitled").session(sessionFor("admin")).with(csrf())
                        .param("html", "<html>SHOULD_NOT_PERSIST</html>")
                        .param("path", "/__admin").param("template", "_master"))
                .andExpect(status().is3xxRedirection());
        assertEquals(before, writer.readBody("acme", "untitled").orElseThrow(), "a rejected save writes nothing");
    }

    /**
     * The live preview of unsaved edits is driven by {@code page-editor.js}, which has no test runner here — so this
     * pins the markup contract the script relies on, and then drives the real endpoint with exactly what the markup
     * carries (its action, its CSRF field, its field names), as the browser would when the script submits it.
     */
    @Test
    void theEditorCarriesWhatTheLivePreviewScriptNeedsAndItsFormReachesTheEndpoint() throws Exception {
        MockHttpSession session = sessionFor("admin");
        String html = mvc.perform(get("/__admin/pages/home/edit").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);

        org.jsoup.nodes.Element form = doc.getElementById("pe-live-form");
        assertTrue(form != null, "the live-preview form is rendered");
        assertEquals("post", form.attr("method"));
        assertEquals("/__admin/pages/home/preview", form.attr("action"), "it posts to the unsaved-content preview");
        assertEquals("/__admin/pages/home/preview", form.attr("data-saved-src"), "the saved preview to fall back to");
        assertTrue(Integer.parseInt(form.attr("data-debounce")) > 0 && Integer.parseInt(form.attr("data-timeout")) > 0);
        assertTrue(form.parents().stream().noneMatch(p -> p.id().equals("pe-form")),
                "not nested in the editor form (forms cannot nest)");
        org.jsoup.nodes.Element csrfField = doc.getElementById("pe-live-csrf");
        org.jsoup.nodes.Element editorCsrf = doc.getElementById("pe-csrf");
        assertFalse(csrfField.attr("value").isBlank(), "the CSRF token is carried");
        assertEquals(editorCsrf.attr("name"), csrfField.attr("name"));
        assertEquals(editorCsrf.attr("value"), csrfField.attr("value"), "the same token the Save form posts");
        assertEquals("html", doc.getElementById("pe-live-html").attr("name"));
        assertEquals("thxml", doc.getElementById("pe-live-thxml").attr("name"));

        org.jsoup.nodes.Element frame = doc.getElementById("pe-preview");
        assertEquals("allow-scripts", frame.attr("sandbox"), "the frame the script clones stays sandboxed");
        assertEquals("/__admin/pages/home/preview", frame.attr("src"), "it starts on the saved draft");
        org.jsoup.nodes.Element badge = doc.getElementById("pe-live-badge");
        assertTrue(badge.hasClass("d-none"), "the unsaved badge starts hidden");
        assertEquals("Unsaved changes", badge.text());
        for (String id : List.of("pe-body", "pe-logic", "pe-body-host", "pe-logic-host", "pe-popout")) {
            assertTrue(doc.getElementById(id) != null, "the script binds to #" + id);
        }
        assertTrue(doc.select("script[src^=/__assets/page-editor.js]").size() == 1, "the script is loaded");
        assertTrue(html.contains(".pe-live-staging"), "the staging-frame rule is in the page style");

        // What the script does on an edit: submit the form, with the editors' contents, as the browser would.
        mvc.perform(post(form.attr("action")).session(session)
                        .param(csrfField.attr("name"), csrfField.attr("value"))
                        .param("html", "<!DOCTYPE html><html><head></head><body><main>LIVE_EDIT_MARKER</main></body></html>")
                        .param("thxml", "<thlogic></thlogic>"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("LIVE_EDIT_MARKER")));
    }

    @Test
    void anOperatorWithoutEditPagesCannotOpenTheEditor() throws Exception {
        mvc.perform(get("/__admin/pages/home/edit").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePagesListLinksToTheEditor() throws Exception {
        mvc.perform(get("/__admin/pages").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/__admin/pages/home/edit")));
    }

    @Test
    void anOperatorWithoutAnyPagePermissionCannotViewTheList() throws Exception {
        // The list gates on EDIT_PAGES or DISABLE_PAGES (grantable), not master/wildcard — a plain OPERATOR
        // holds neither, so it is refused; a granted editor (not shown here) would pass.
        mvc.perform(get("/__admin/pages").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
