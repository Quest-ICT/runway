package mx.quest.runway.page;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.TemplateCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Content-change auditing: a real page save/delete is sent to the site's audit log; preview scratch is not. */
class PageWriteAuditTest {

    private static final String SITE = "acme";

    @TempDir
    Path external;
    private SiteLogService logs;
    private PageWriteService writer;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site(SITE, "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        PageStore pages = new PageStore(layout, yaml);
        logs = mock(SiteLogService.class);
        writer = new PageWriteService(layout, yaml, pages, mock(TemplateCacheService.class),
                new PageAuthoringValidator(sites, pages), null, logs);
        Path templates = layout.siteDir(SITE).resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
    }

    private Page page(String slug) {
        return new Page(slug, "/" + slug, Map.of(), List.of(), Map.of("en", "T"), Map.of(), "", "_master", false, false, null, null);
    }

    @Test
    void savingAndDeletingARealPageIsAuditedButPreviewScratchIsNot() {
        writer.save(SITE, page("about"), "<html/>", null);
        verify(logs).audit(eq(SITE), anyString(), eq("page.saved"), eq("about"));

        writer.delete(SITE, "about");
        verify(logs).audit(eq(SITE), anyString(), eq("page.deleted"), eq("about"));

        // A preview scratch write + cleanup must never be audited.
        writer.writeScratch(SITE, page("rw-preview-xyz"), "<html/>", null);
        writer.delete(SITE, "rw-preview-xyz");
        verify(logs, never()).audit(eq(SITE), anyString(), anyString(), contains("rw-preview"));
    }

    @Test
    void publishingAndUnpublishingAreAuditedButANoOpFlipIsNot() {
        writer.save(SITE, page("about"), "<html/>", null); // saved published (draft=false)

        writer.setDraft(SITE, "about", false); // already published — a no-op
        verify(logs, never()).audit(eq(SITE), anyString(), eq("page.published"), anyString());

        writer.setDraft(SITE, "about", true);
        verify(logs).audit(eq(SITE), anyString(), eq("page.unpublished"), eq("about"));

        writer.setDraft(SITE, "about", false);
        verify(logs).audit(eq(SITE), anyString(), eq("page.published"), eq("about"));
    }
}
