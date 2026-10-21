package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The starter-template seeder: copies the chosen template's master + home page into a new site, idempotently. */
class TenantSeederTest {

    @TempDir
    Path external;

    private StorageLayout layout() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return StorageLayout.from(props);
    }

    private SeedTemplateStore store(StorageLayout layout) {
        return new SeedTemplateStore(layout, new YamlStore());
    }

    @Test
    void seedsAStyledMasterAndAHomePageThatPullsIt() throws IOException {
        StorageLayout layout = layout();
        new TenantSeeder(layout, store(layout)).seed("demo");
        Path t = layout.siteDir("demo").resolve("templates");

        for (String f : new String[] {"_master.html", "_master.th.xml", "index.html", "index.th.xml", "index.meta.yml"}) {
            assertTrue(Files.exists(t.resolve(f)), f + " seeded");
        }
        assertTrue(Files.readString(t.resolve("_master.html")).contains("<style>"), "master is self-styled");
        assertTrue(Files.readString(t.resolve("index.th.xml")).contains("${master}"), "home pulls the master safely");
        assertFalse(Files.readString(t.resolve("index.meta.yml")).contains("draft: true"), "home is published");
    }

    @Test
    void isIdempotentAndNeverClobbersATenantsOwnMaster() throws IOException {
        StorageLayout layout = layout();
        Path t = layout.siteDir("demo").resolve("templates");
        Files.createDirectories(t);
        Files.writeString(t.resolve("_master.html"), "MY OWN MASTER");

        new TenantSeeder(layout, store(layout)).seed("demo");

        assertEquals("MY OWN MASTER", Files.readString(t.resolve("_master.html")), "the tenant's own master is untouched");
        assertFalse(Files.exists(t.resolve("index.html")), "seeding is skipped entirely once a master exists");
    }

    @Test
    void seedsFromTheDefaultTemplateOverrideWhenPresent() throws IOException {
        StorageLayout layout = layout();
        SeedTemplateStore store = store(layout);
        store.save("default", "_master.html", "OVERRIDDEN MASTER");   // operator edited the default template

        new TenantSeeder(layout, store).seed("demo");
        Path t = layout.siteDir("demo").resolve("templates");

        assertEquals("OVERRIDDEN MASTER", Files.readString(t.resolve("_master.html")),
                "a new site is born from the default override");
        assertTrue(Files.readString(t.resolve("index.th.xml")).contains("${master}"), "other files fall back to default");
    }

    @Test
    void seedsFromAChosenNamedTemplate() throws IOException {
        StorageLayout layout = layout();
        SeedTemplateStore store = store(layout);
        store.createTemplate("corp", "Corporate", "", null);
        store.save("corp", "_master.html", "CORP MASTER");

        new TenantSeeder(layout, store).seed("acme", "corp");

        assertEquals("CORP MASTER", Files.readString(layout.siteDir("acme").resolve("templates").resolve("_master.html")),
                "the site is born from the chosen template");
    }

    @Test
    void anUnknownTemplateFallsBackToTheDefault() throws IOException {
        StorageLayout layout = layout();
        new TenantSeeder(layout, store(layout)).seed("demo", "since-deleted");
        assertTrue(Files.readString(layout.siteDir("demo").resolve("templates").resolve("_master.html")).contains("<style>"),
                "a missing template never blocks seeding — it uses the default");
    }

    @Test
    void anUnsafeTemplateIdNeverBlocksSeedingAndUsesTheDefault() throws IOException {
        StorageLayout layout = layout();
        // A hand-crafted unsafe id must degrade to the default, never throw out of the best-effort seeder.
        new TenantSeeder(layout, store(layout)).seed("demo", "../../etc/passwd");
        assertTrue(Files.readString(layout.siteDir("demo").resolve("templates").resolve("_master.html")).contains("<style>"),
                "an unsafe template id seeds from the default without throwing");
    }

    /**
     * A new tenant is born with BOTH starter pages, not just the home page.
     *
     * <p>Pinned because the seed file set is the closed allowlist in {@link SeedTemplateStore#FILES}: a page whose
     * three files are not listed there is silently not copied, producing a site that looks correct until someone
     * follows the nav link to a page that was never written. Naming the About files explicitly is what makes that
     * failure loud instead of subtle.
     */
    @Test
    void seedsBothStarterPagesNotJustTheHomePage() throws IOException {
        StorageLayout layout = layout();
        new TenantSeeder(layout, store(layout)).seed("demo");
        Path t = layout.siteDir("demo").resolve("templates");

        for (String f : List.of("_master.html", "_master.th.xml",
                "index.html", "index.th.xml", "index.meta.yml",
                "about.html", "about.th.xml", "about.meta.yml")) {
            assertTrue(Files.exists(t.resolve(f)), "starter file missing from the new tenant: " + f);
        }
        assertTrue(Files.readString(t.resolve("about.th.xml")).contains("${master}"),
                "the about page pulls the same master as the home page");
        assertTrue(Files.readString(t.resolve("about.meta.yml")).contains("/about"),
                "the about page is routable");
    }

    /**
     * The starter master binds the page title, the description and the document language.
     *
     * <p>These are the three defects that made every new site ship with an identical {@code <title>Site</title>},
     * no {@code <meta name="description">} at all, and a hardcoded {@code lang="en"} regardless of the tenant's
     * locale — invisible on the bundled demo sites, which carried their own masters, and so shipped for a long
     * time as the out-of-box experience.
     */
    @Test
    void theStarterMasterBindsTitleDescriptionAndLanguage() throws IOException {
        StorageLayout layout = layout();
        new TenantSeeder(layout, store(layout)).seed("demo");
        Path t = layout.siteDir("demo").resolve("templates");

        String masterHtml = Files.readString(t.resolve("_master.html"));
        String masterLogic = Files.readString(t.resolve("_master.th.xml"));
        String indexLogic = Files.readString(t.resolve("index.th.xml"));

        // Bindable seams exist in the markup...
        assertTrue(masterHtml.contains("id=\"m-title\""), "the title element must be bindable");
        assertTrue(masterHtml.contains("id=\"m-desc\""), "a description element must exist to bind");
        // ...and the decoupled logic binds them to PER-PAGE values rather than constants.
        assertTrue(masterLogic.contains("${pageTitle}"), "the title must come from the page");
        assertTrue(masterLogic.contains("${pageDescription}"), "the description must come from the page");
        assertTrue(indexLogic.contains("lang=${#locale.language}"), "the language must follow the rendered locale");
        assertFalse(masterHtml.contains("<title>Site</title>"), "the hardcoded placeholder title must be gone");

        // The decoupled contract: no Thymeleaf may appear in the .html itself.
        assertFalse(masterHtml.matches("(?s).*\\sth:[a-z]+=.*"), "the .html must stay free of Thymeleaf");
    }
}
