package mx.quest.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders the console templates ({@code admin-list}, {@code admin-edit}) through the real decoupled-logic pipeline
 * with the models the controller builds — a binding typo (a {@code /} where {@code //} belongs, a broken selector) is
 * a RUNTIME failure the unit tests can't see; this test turns it into a build failure. And it holds the templates to
 * the {@code @ConsoleScreen} contract: the only shell variables they read are {@code _csrf} (and their own
 * controller's model), they reach no bean, and every {@code #{…}} key is in this plugin's own bundle.
 */
class BlogAdminTemplatesRenderTest {

    /** What the controller puts in the model for these templates — the plugin's own variables. */
    private static final Set<String> OWN_MODEL = Set.of("rows", "row", "blogEnabled", "entry", "bodies", "creating",
            "shadowedBy", "siteLocaleTags", "defaultLocaleTag", "formAction", "lang");
    /** What the shell promises a console screen's content template (SPI {@code ConsoleScreen}). */
    private static final Set<String> SHELL_CONTRACT = Set.of("_csrf", "tenantSiteId");

    private static String live(String file) throws IOException {
        return Files.readString(Path.of("templates", file), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
    }

    private static Map<String, Object> withCsrf(Map<String, Object> vars) {
        Map<String, Object> all = new HashMap<>(vars);
        // The shell's CsrfToken, as SpEL reads it (parameterName, token).
        all.put("_csrf", Map.of("parameterName", "_csrf", "token", "tok-123"));
        return all;
    }

    @Test
    void theConsoleTemplatesReadOnlyTheirOwnModelAndThePromisedShellVariables() throws IOException {
        Pattern root = Pattern.compile("\\$\\{\\s*!?\\s*([A-Za-z_@#][\\w]*)");
        Pattern message = Pattern.compile("#\\{([^}(]+)");
        Set<String> bundle = BlogConfigTest.keys("i18n/messages.properties");
        Set<String> roots = new TreeSet<>();
        for (String file : List.of("admin-list.th.xml", "admin-edit.th.xml")) {
            String logic = live(file);
            Matcher m = root.matcher(logic);
            while (m.find()) {
                roots.add(m.group(1));
            }
            Matcher k = message.matcher(logic);
            while (k.find()) {
                assertTrue(bundle.contains(k.group(1).trim()), file + ": #{" + k.group(1)
                        + "} is not in this plugin's bundle (no kernel console key is promised)");
            }
        }
        for (String r : roots) {
            assertFalse(r.startsWith("@"), "no bean reference in the console templates: " + r);
            assertTrue(r.startsWith("#") || OWN_MODEL.contains(r) || SHELL_CONTRACT.contains(r),
                    "a variable neither the controller nor the shell contract provides: " + r);
        }
        assertTrue(roots.contains("_csrf"), "every mutating form carries the CSRF token");
    }

    @Test
    void theListScreenRendersRowsBadgesPostFormsAndCsrf(@TempDir Path tmp) {
        BlogEntry published = new BlogEntry("hello", Map.of("en", "Hello"), Map.of(), List.of("en", "es"),
                null, Map.of(), null, true, "2026-07-17", null, null);
        BlogEntry draft = new BlogEntry("secret", Map.of("en", "Secret"), Map.of(), List.of(),
                null, Map.of(), null, false, null, null, null);
        String html = new TemplateHarness(tmp).render("__plugin__/blog/admin-list", Locale.ENGLISH, withCsrf(Map.of(
                "rows", List.of(new BlogAdminController.EntryRow(published, "/blog/hello"),
                        new BlogAdminController.EntryRow(draft, "/blog/secret")),
                "blogEnabled", true)));

        assertTrue(html.contains("/blog/hello") && html.contains("/blog/secret"), "the resolved public paths");
        assertTrue(html.contains("en, es"), "the languages");
        assertTrue(html.contains("2026-07-17"), "the date renders");
        assertTrue(html.contains(">Published<") && html.contains(">Draft<"), "the plugin's badges");
        assertTrue(html.contains("bg-green-lt") && html.contains("bg-yellow-lt"));
        assertTrue(html.contains("href=\"/__admin/blog/hello/edit\""), "the edit link (a GET that only renders)");
        assertTrue(html.contains("method=\"post\" action=\"/__admin/blog/hello/publish\""), "publish is a POST");
        assertTrue(html.contains("method=\"post\" action=\"/__admin/blog/secret/delete\""), "delete is a POST");
        assertEquals(4, html.split("name=\"_csrf\" value=\"tok-123\"", -1).length - 1,
                "every mutating form (2 rows × publish + delete) carries the console's CSRF token");
        assertFalse(html.contains("href=\"/__admin/blog/hello/publish\"") || html.contains("/delete\" href"),
                "no link points at a mutation");
        assertTrue(html.contains(">Site<"), "the pretitle comes from the plugin's own bundle");
        assertFalse(html.contains("??"), "every message key resolved");
        assertFalse(html.contains("id=\"bl-disabled\""), "no banner when enabled");
        assertFalse(html.contains("id=\"bl-empty\""), "no empty row with entries");
    }

    @Test
    void theListScreenShowsTheEmptyRowAndTheDisabledBanner(@TempDir Path tmp) {
        TemplateHarness harness = new TemplateHarness(tmp);
        String disabled = harness.render("__plugin__/blog/admin-list", Locale.of("es"), withCsrf(Map.of(
                "rows", List.<BlogAdminController.EntryRow>of(), "blogEnabled", false)));
        assertTrue(disabled.contains("id=\"bl-disabled\""), "disabled banner present");
        assertTrue(disabled.contains("id=\"bl-empty\""), "the empty row");
        assertTrue(disabled.contains(">Sitio<"), "Spanish console words from the plugin's bundle");
        assertFalse(disabled.contains("??"));
    }

    @Test
    void theEditorRendersPerLanguageCardsForBothCreateAndEdit(@TempDir Path tmp) {
        TemplateHarness harness = new TemplateHarness(tmp);
        BlogEntry entry = new BlogEntry("hello", Map.of("en", "Hello", "es", "Hola"),
                Map.of("en", "Desc"), List.of("en"), "hello-slug", Map.of("es", "hola-slug"),
                "/custom", true, "2026-07-17", "Jane Doe", null);
        Map<String, Object> vars = Map.of(
                "formAction", "/__admin/blog/hello",
                "entry", entry,
                "bodies", Map.of("en", "# Body EN", "es", ""),
                "creating", false,
                "shadowedBy", List.of("about (en)"),
                "siteLocaleTags", List.of("en", "es"),
                "defaultLocaleTag", "en");
        String html = harness.render("__plugin__/blog/admin-edit", Locale.ENGLISH, withCsrf(vars));

        assertTrue(html.contains("method=\"post\" action=\"/__admin/blog/hello\""), "edit mode POSTs to the entry");
        assertTrue(html.contains("name=\"_csrf\" value=\"tok-123\""), "with the console's CSRF token");
        assertTrue(html.contains("name=\"title_en\"") && html.contains("name=\"title_es\""),
                "one language card per site locale, fields renamed per language");
        assertTrue(html.contains("value=\"Hello\"") && html.contains("value=\"Hola\""), "titles prefilled");
        assertTrue(html.contains("value=\"hello-slug\""), "the default language's slug is the base slug");
        assertTrue(html.contains("value=\"hola-slug\""), "the localized slug prefills");
        assertTrue(html.contains("name=\"body_en\"") && html.contains("# Body EN"), "the Markdown body prefills");
        assertTrue(html.contains("value=\"/custom\""), "the custom path prefills");
        assertTrue(html.contains("value=\"Jane Doe\""), "the author prefills");
        assertTrue(html.contains("name=\"locales_en\" checked"), "a published language is ticked");
        assertTrue(html.contains("readonly"), "the id is readonly on edit");
        assertTrue(html.contains("about (en)"), "the reverse advisory renders the winning page");
        assertTrue(html.contains("en · default"), "the default language is marked");
        assertFalse(html.contains("??"), "every message key resolved");

        Map<String, Object> creating = Map.of(
                "formAction", "/__admin/blog/create",
                "entry", new BlogEntry("new-entry", Map.of(), Map.of(), List.of(), null, Map.of(),
                        null, false, null, null, null),
                "bodies", Map.<String, String>of(),
                "creating", true,
                "shadowedBy", List.<String>of(),
                "siteLocaleTags", List.of("en"),
                "defaultLocaleTag", "en");
        String createHtml = harness.render("__plugin__/blog/admin-edit", Locale.ENGLISH, withCsrf(creating));
        assertTrue(createHtml.contains("action=\"/__admin/blog/create\""), "create mode posts to /create");
        assertFalse(createHtml.contains("readonly"), "the id is editable on create");
        assertFalse(createHtml.contains("id=\"be-shadowed\""), "no advisory on create");
    }
}
