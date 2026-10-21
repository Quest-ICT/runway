package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Locale;
import mx.quest.runway.site.SeedTemplateStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** The app-realm Site templates catalog controller: catalog/create/import/files/edit/save/reset/delete/export. */
class SiteTemplatesControllerTest {

    @TempDir
    Path external;

    private SeedTemplateStore catalog;

    private SiteTemplatesController controller() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        catalog = new SeedTemplateStore(layout, new YamlStore());
        var activityProps = new mx.quest.runway.log.AppActivityLogProperties();
        activityProps.setEnabled(false); // no audit side effects in this unit test
        return new SiteTemplatesController(catalog, new mx.quest.runway.log.AppActivityLog(layout, activityProps),
                operatorMessages());
    }

    /** The console's own bundles, as the running server reads them. */
    private static ResourceBundleMessageSource operatorMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/operator-messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false); // the workstation's es_MX must not leak into the EN assertions
        return source;
    }

    /** Runs {@code action} with the console in {@code locale}, as the operator locale resolver would set it. */
    private static void in(Locale locale, Runnable action) {
        LocaleContextHolder.setLocale(locale);
        try {
            action.run();
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void messagesAreInTheConsoleLanguage() {
        SiteTemplatesController c = controller();
        catalog.createTemplate("corp", "Corporate", "", null);
        in(Locale.ENGLISH, () -> {
            RedirectAttributesModelMap redirect = redirect();
            c.details("corp", "Corporate 2", "", redirect);
            assertEquals("Saved the name and description of template \"corp\".",
                    redirect.getFlashAttributes().get("flash"));
            RedirectAttributesModelMap refused = redirect();
            c.details("default", "Mine", "", refused);
            assertEquals("The built-in default template's name cannot be changed.",
                    refused.getFlashAttributes().get("error"));
        });
        in(Locale.forLanguageTag("es"), () -> {
            RedirectAttributesModelMap redirect = redirect();
            c.details("corp", "Corporate 3", "", redirect);
            assertEquals("Se guardaron el nombre y la descripción de la plantilla \"corp\".",
                    redirect.getFlashAttributes().get("flash"));
            RedirectAttributesModelMap tooLong = redirect();
            c.details("corp", "x".repeat(129), "", tooLong);
            assertEquals("El nombre tiene más de 128 caracteres.", tooLong.getFlashAttributes().get("error"));
            RedirectAttributesModelMap none = redirect();
            c.files("ghost", new ConcurrentModel(), none);
            assertEquals("No existe la plantilla \"ghost\".", none.getFlashAttributes().get("error"));
            RedirectAttributesModelMap all = redirect();
            catalog.save("default", "_master.html", "a");
            catalog.save("default", "index.html", "b");
            c.resetAll("default", all);
            assertEquals("Se restablecieron 2 archivos al valor predeterminado de la plataforma.",
                    all.getFlashAttributes().get("flash"));
        });
    }

    private RedirectAttributesModelMap redirect() {
        return new RedirectAttributesModelMap();
    }

    @Test
    void catalogRendersThroughTheOperatorLayout() {
        SiteTemplatesController c = controller();
        ConcurrentModel model = new ConcurrentModel();
        assertEquals("operator/op-layout", c.catalog(model));
        assertEquals("operator/app/site-templates", model.get("contentTemplate"));
        assertFalse(((java.util.List<?>) model.get("templates")).isEmpty(), "the default template is always listed");
    }

    @Test
    void createRedirectsToTheNewTemplate() {
        SiteTemplatesController c = controller();
        RedirectAttributesModelMap redirect = redirect();
        String view = c.create("corp", "Corporate", "look", "default", redirect);
        assertEquals("redirect:/__app/site-templates/corp", view);
        assertTrue(catalog.templateExists("corp"));
        assertTrue(redirect.getFlashAttributes().containsKey("flash"));
    }

    @Test
    void createRejectsAReservedIdWithAFlash() {
        SiteTemplatesController c = controller();
        RedirectAttributesModelMap redirect = redirect();
        assertEquals("redirect:/__app/site-templates", c.create("default", "x", "", null, redirect));
        assertTrue(redirect.getFlashAttributes().containsKey("error"));
    }

    @Test
    void filesRendersForATemplateAndRedirectsForAnUnknownOne() {
        SiteTemplatesController c = controller();
        catalog.createTemplate("corp", "Corporate", "", null);
        ConcurrentModel model = new ConcurrentModel();
        assertEquals("operator/op-layout", c.files("corp", model, redirect()));
        assertEquals("operator/app/site-template-files", model.get("contentTemplate"));
        assertNotNull(model.get("files"));

        RedirectAttributesModelMap redirect = redirect();
        assertEquals("redirect:/__app/site-templates", c.files("ghost", new ConcurrentModel(), redirect));
        assertTrue(redirect.getFlashAttributes().containsKey("error"));
    }

    @Test
    void editSaveResetOnAFileFlowThroughTheStore() {
        SiteTemplatesController c = controller();
        ConcurrentModel model = new ConcurrentModel();
        assertEquals("operator/op-layout", c.edit("default", "_master.html", model, redirect()));
        assertTrue(((String) model.get("content")).contains("<style>"), "edit loads the effective content");

        assertEquals("redirect:/__app/site-templates/default",
                c.save("default", "index.html", "HELLO", redirect()));
        assertEquals("HELLO", catalog.readText("default", "index.html"));

        c.reset("default", "index.html", redirect());
        assertFalse(catalog.isOverridden("default", "index.html"), "reset dropped the override");
    }

    @Test
    void resetAllClearsEveryOverride() {
        SiteTemplatesController c = controller();
        catalog.save("default", "_master.html", "a");
        catalog.save("default", "index.html", "b");
        c.resetAll("default", redirect());
        assertFalse(catalog.isOverridden("default", "_master.html"));
        assertFalse(catalog.isOverridden("default", "index.html"));
    }

    @Test
    void deleteRemovesATemplateButRefusesTheDefault() {
        SiteTemplatesController c = controller();
        catalog.createTemplate("temp", "Temp", "", null);
        assertEquals("redirect:/__app/site-templates", c.delete("temp", redirect()));
        assertFalse(catalog.templateExists("temp"));

        RedirectAttributesModelMap redirect = redirect();
        c.delete("default", redirect);
        assertTrue(redirect.getFlashAttributes().containsKey("error"));
        assertTrue(catalog.templateExists("default"));
    }

    @Test
    void exportReturnsAZipAttachment() {
        SiteTemplatesController c = controller();
        ResponseEntity<byte[]> response = c.export("default");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
        assertTrue(response.getHeaders().getContentDisposition().getFilename().endsWith(".zip"));
    }

    @Test
    void exportOfAnUnknownTemplateIs404() {
        SiteTemplatesController c = controller();
        assertEquals(404, c.export("ghost").getStatusCode().value());
    }

    @Test
    void importRoundTripsAnExportedTemplate() {
        SiteTemplatesController c = controller();
        byte[] zip = catalog.exportZip("default");
        MockMultipartFile file = new MockMultipartFile("file", "default.zip", "application/zip", zip);
        String view = c.importZip("imported", "Imported", "", file, redirect());
        assertEquals("redirect:/__app/site-templates/imported", view);
        assertTrue(catalog.templateExists("imported"));
    }

    @Test
    void anUnsafeTemplateIdDegradesGracefullyInsteadOf500() {
        SiteTemplatesController c = controller();
        RedirectAttributesModelMap redirect = redirect();
        assertEquals("redirect:/__app/site-templates", c.files("../../etc", new ConcurrentModel(), redirect),
                "an unsafe id is treated as not-found, not a 500");
        assertTrue(redirect.getFlashAttributes().containsKey("error"));
        assertEquals(404, c.export("../../etc").getStatusCode().value(), "export of an unsafe id is 404, not 500");
    }

    @Test
    void importRejectsAnEmptyUpload() {
        SiteTemplatesController c = controller();
        MockMultipartFile empty = new MockMultipartFile("file", "x.zip", "application/zip", new byte[0]);
        RedirectAttributesModelMap redirect = redirect();
        assertEquals("redirect:/__app/site-templates", c.importZip("x", "X", "", empty, redirect));
        assertTrue(redirect.getFlashAttributes().containsKey("error"));
    }

    @Test
    void detailsRenameATemplateAndKeepItsIdAndFiles() {
        SiteTemplatesController c = controller();
        catalog.createTemplate("corp", "Corporate", "old look", null);
        byte[] master = catalog.read("corp", "_master.html");
        RedirectAttributesModelMap redirect = redirect();
        assertEquals("redirect:/__app/site-templates/corp", c.details("corp", "  Corporate 2026 ", " new look ", redirect));
        assertTrue(redirect.getFlashAttributes().containsKey("flash"));
        SeedTemplateStore.SiteTemplate t = catalog.template("corp");
        assertEquals("Corporate 2026", t.name());
        assertEquals("new look", t.description());
        assertTrue(catalog.templateExists("corp"), "the id is unchanged");
        org.junit.jupiter.api.Assertions.assertArrayEquals(master, catalog.read("corp", "_master.html"),
                "the files are untouched");

        c.details("corp", " ", "", redirect());
        assertEquals("corp", catalog.template("corp").name(), "a blank name falls back to the id");
    }

    @Test
    void detailsRefuseTheBuiltInDefaultAnUnknownTemplateAndOverlongText() {
        SiteTemplatesController c = controller();
        catalog.createTemplate("corp", "Corporate", "", null);
        for (String[] attempt : new String[][] {{"default", "Mine", ""}, {"ghost", "X", ""},
                {"corp", "x".repeat(129), ""}, {"corp", "ok", "d".repeat(1001)}}) {
            RedirectAttributesModelMap redirect = redirect();
            c.details(attempt[0], attempt[1], attempt[2], redirect);
            assertTrue(redirect.getFlashAttributes().containsKey("error"), "refused: " + attempt[0]);
        }
        assertEquals("Corporate", catalog.template("corp").name(), "nothing changed");
        assertFalse(catalog.templateExists("ghost"), "no template is conjured up");
    }
}
