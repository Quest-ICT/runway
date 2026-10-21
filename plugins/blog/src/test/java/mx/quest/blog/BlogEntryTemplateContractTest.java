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
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The entry body templates against the <b>documented body-template render model</b> — the SPI contract on
 * {@code ContentSource} / {@code ContentRef.bodyTemplate} for {@code ${master}} and {@code ${content.*}}. The kernel
 * promises exactly {@code master}, {@code content.{id, sourceKey, title, description, bodyHtml, attributes, updatedAt}},
 * {@code locale} and {@code urls}; nothing else.
 *
 * <ul>
 *   <li>Statically: every {@code ${…}} expression in the shipped entry templates starts from one of those four
 *       variables, and every {@code content.} key it reads is one the contract lists.</li>
 *   <li>Dynamically: an entry stored by {@link BlogStore} and projected by {@link BlogContentSource} renders through
 *       the real decoupled templates with a model built exactly to the contract — the tenant's master chrome around
 *       the Markdown body, the plugin's own labels, and the byline in each of its four states.</li>
 * </ul>
 * The real kernel render (routing, the locale ladder, the master resolution) is exercised by
 * {@code scripts/verify-against-host.sh}.
 */
class BlogEntryTemplateContractTest {

    private static final Set<String> MODEL_ROOTS = Set.of("master", "content", "locale", "urls");
    private static final Set<String> CONTENT_KEYS =
            Set.of("id", "sourceKey", "title", "description", "bodyHtml", "attributes", "updatedAt");
    private static final List<String> ENTRY_TEMPLATES = List.of("entry-classic", "entry-minimal");

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);

    private static String liveLogic(String template) throws IOException {
        return Files.readString(Path.of("templates", template + ".th.xml"), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    void theEntryTemplatesReadOnlyTheDocumentedRenderModel() throws IOException {
        Pattern expression = Pattern.compile("\\$\\{([^}]*)}");
        Pattern root = Pattern.compile("^\\s*([A-Za-z_][\\w]*)");
        Pattern contentKey = Pattern.compile("\\bcontent\\.(\\w+)");
        Set<String> roots = new TreeSet<>();
        Set<String> keys = new TreeSet<>();
        for (String template : ENTRY_TEMPLATES) {
            Matcher m = expression.matcher(liveLogic(template));
            while (m.find()) {
                Matcher r = root.matcher(m.group(1));
                assertTrue(r.find(), template + ": an expression with no variable root: ${" + m.group(1) + "}");
                roots.add(r.group(1));
                Matcher k = contentKey.matcher(m.group(1));
                while (k.find()) {
                    keys.add(k.group(1));
                }
            }
        }
        assertTrue(MODEL_ROOTS.containsAll(roots), "variables outside the contract: " + roots);
        assertTrue(CONTENT_KEYS.containsAll(keys), "content keys outside the contract: " + keys);
        assertEquals(Set.of("master", "content", "urls"), roots, "the templates use the chrome, the payload and links");
    }

    @Test
    void theEntryTemplatesReferenceNoBeanAndOnlyThePluginsOwnMessages() throws IOException {
        Set<String> bundle = BlogConfigTest.keys("i18n/messages.properties");
        Pattern message = Pattern.compile("#\\{([^}(]+)");
        for (String template : ENTRY_TEMPLATES) {
            String logic = liveLogic(template);
            assertFalse(logic.matches("(?s).*\\$\\{\\s*@.*"), template + " must not reach a bean (not in the contract)");
            Matcher m = message.matcher(logic);
            while (m.find()) {
                assertTrue(bundle.contains(m.group(1).trim()), template + ": #{" + m.group(1)
                        + "} is not in this plugin's bundle (a public page renders with the site's bundle + ours)");
            }
        }
    }

    /** The model the kernel builds for a source item's 200 render, exactly as the SPI documents it. */
    private static Map<String, Object> contractModel(String master, ContentRef ref, ContentBody body, Locale locale) {
        Map<String, Object> content = new HashMap<>();
        content.put("id", ref.id());
        content.put("sourceKey", BlogContentSource.KEY);
        content.put("title", ref.titleFor(locale));
        content.put("description", ref.descriptionFor(locale));
        content.put("bodyHtml", body.bodyHtml());
        content.put("attributes", body.attributes());
        content.put("updatedAt", ref.updatedAt() == null ? "" : ref.updatedAt().toString());
        return Map.of("master", master, "content", Map.copyOf(content), "locale", locale,
                "urls", Map.of("index", "/", "blog:" + ref.id(), ref.pathFor(locale)));
    }

    private record Rig(TemplateHarness harness, BlogStore store, BlogContentSource source, String master) {

        String render(String id, Locale locale) {
            ContentRef ref = source.enumerate(SITE).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            return harness.render(ref.bodyTemplate(), locale,
                    contractModel(master, ref, source.body(SITE, id, locale), locale));
        }
    }

    private static Rig rig(Path tmp, Map<String, Object> settings) throws IOException {
        Path sites = tmp.resolve("sites");
        Files.createDirectories(sites.resolve("acme"));
        BlogStore store = new BlogStore(new TempDirSiteFiles(sites, "blog"));
        BlogContentSource source = new BlogContentSource(store, new FakePluginSettings().site("acme", settings),
                new FakeContentCatalog().site("acme"));
        TemplateHarness harness = new TemplateHarness(tmp.resolve("templates"));
        String master = harness.master("sites/acme/_master",
                "<!DOCTYPE html><html><head><title>ACME-MASTER</title></head><body>"
                        + "<header id=\"site-header\">acme header</header>"
                        + "<main id=\"content\">m</main>"
                        + "<footer id=\"site-footer\">acme footer</footer></body></html>");
        return new Rig(harness, store, source, master);
    }

    private static BlogEntry entry(String id, String publishedOn, String author) {
        return new BlogEntry(id, Map.of("en", "My first post", "es", "Mi primera entrada"),
                Map.of("en", "The very first."), List.of("en", "es"), null, Map.of("es", "primera-entrada"),
                null, true, publishedOn, author, null);
    }

    @Test
    void aMarkdownEntryRendersInsideTheTenantsChromeWithPluginLabels(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, Map.of());
        rig.store().save("acme", entry("first-post", "2026-07-17", null),
                Map.of("en", "Hello **world**.", "es", "Hola **mundo**."));

        String html = rig.render("first-post", EN);
        assertTrue(html.contains("ACME-MASTER"), "the TENANT's master head wraps the entry");
        assertTrue(html.contains("acme header") && html.contains("acme footer"), "tenant chrome seams");
        assertTrue(html.contains("My first post"), "the entry title");
        assertTrue(html.contains("Hello <strong>world</strong>."), "the Markdown body, rendered");
        assertTrue(html.contains("Published on 2026-07-17") && html.contains("datetime=\"2026-07-17\""),
                "the plugin's own #{} label + the publication date");
        assertTrue(html.contains("href=\"/\""), "the back link reads urls['index']");

        String es = rig.render("first-post", ES);
        assertTrue(es.contains("Mi primera entrada") && es.contains("Hola <strong>mundo</strong>."), "the es payload");
        assertTrue(es.contains("Publicado el 2026-07-17"), "the plugin's es label");
    }

    @Test
    void anAuthoredEntryRendersAByline(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, Map.of());
        rig.store().save("acme", entry("authored", "2026-07-17", "Jane Doe"), Map.of("en", "Body."));
        String html = rig.render("authored", EN);
        assertTrue(html.contains("By Jane Doe"), () -> "expected a rendered byline; got:\n" + html);
        assertTrue(html.contains("2026-07-17"), "the date still renders alongside it");
    }

    /** The meta line used to render only with a date; an undated but authored post must keep its byline. */
    @Test
    void anUndatedButAuthoredEntryStillRendersItsByline(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, Map.of());
        rig.store().save("acme", entry("undated", null, "Richard Roe"), Map.of("en", "Body."));
        String html = rig.render("undated", EN);
        assertTrue(html.contains("By Richard Roe"), () -> "an undated entry must still show its byline:\n" + html);
        assertFalse(html.contains("Published on"), "…and must not show an empty date label");
    }

    @Test
    void anEntryWithNeitherAuthorNorDateRendersNoMetaLine(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, Map.of());
        rig.store().save("acme", entry("bare", null, null), Map.of("en", "Body."));
        String html = rig.render("bare", EN);
        assertFalse(html.contains("blog-entry-meta"), () -> "no meta line when there is nothing in it:\n" + html);
    }

    @Test
    void theMinimalTemplateRendersTitleAndBodyOnly(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, Map.of("template", "entry-minimal"));
        rig.store().save("acme", entry("min", "2026-07-17", "Jane Doe"), Map.of("en", "Just **this**."));
        String html = rig.render("min", EN);
        assertTrue(html.contains("ACME-MASTER") && html.contains("acme footer"), "the same tenant chrome");
        assertTrue(html.contains("My first post") && html.contains("Just <strong>this</strong>."), "title + body");
        assertFalse(html.contains("Jane Doe") || html.contains("Published on") || html.contains("entry-back"),
                "no byline, no date, no back link");
    }
}
