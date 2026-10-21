package mx.quest.runway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The cheap renderability check: home routable (via the REAL router) + a master present, memoized and evictable. */
class SiteContentReadinessTest {

    private static final Site SITE = new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH,
            List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE, false, null);

    private static StorageLayout layout(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root);
        return StorageLayout.from(props);
    }

    private static PageRouting routingThatFinds(boolean home) {
        PageRouting routing = mock(PageRouting.class);
        Optional<Page> found = home
                ? Optional.of(new Page("index", "/", java.util.Map.of(), List.of(), java.util.Map.of(),
                        java.util.Map.of(), "", "_master", false, false, null, null))
                : Optional.empty();
        when(routing.find(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("/"))).thenReturn(found);
        return routing;
    }

    private static HealthProperties enabled() {
        return new HealthProperties();
    }

    private static void writeMaster(Path root) throws IOException {
        Path templates = layout(root).siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html></html>");
    }

    @Test
    void okWhenTheHomeRoutesAndTheMasterExists(@TempDir Path root) throws IOException {
        writeMaster(root);
        SiteContentReadiness readiness = new SiteContentReadiness(layout(root), routingThatFinds(true), enabled());
        assertThat(readiness.check(SITE)).isEqualTo(SiteHealth.ContentCheck.OK);
    }

    @Test
    void missingHomeIsReportedAheadOfTheMasterBecauseItIsTheDefinitiveFault(@TempDir Path root) {
        SiteContentReadiness readiness = new SiteContentReadiness(layout(root), routingThatFinds(false), enabled());
        assertThat(readiness.check(SITE)).isEqualTo(SiteHealth.ContentCheck.MISSING_HOME);
    }

    @Test
    void missingMasterIsReportedWhenTheHomeRoutesButNoMasterFileExists(@TempDir Path root) {
        SiteContentReadiness readiness = new SiteContentReadiness(layout(root), routingThatFinds(true), enabled());
        assertThat(readiness.check(SITE)).isEqualTo(SiteHealth.ContentCheck.MISSING_MASTER);
    }

    @Test
    void skippedWhenTheCheckIsDisabled(@TempDir Path root) {
        HealthProperties off = new HealthProperties();
        off.setContentCheck(false);
        assertThat(new SiteContentReadiness(layout(root), routingThatFinds(false), off).check(SITE))
                .isEqualTo(SiteHealth.ContentCheck.SKIPPED);
    }

    @Test
    void theResultIsMemoizedAndEvictedOnASiteChange(@TempDir Path root) throws IOException {
        SiteContentReadiness readiness = new SiteContentReadiness(layout(root), routingThatFinds(true), enabled());
        assertThat(readiness.check(SITE)).isEqualTo(SiteHealth.ContentCheck.MISSING_MASTER);

        writeMaster(root);
        assertThat(readiness.check(SITE))
                .as("still memoized — the TTL has not elapsed")
                .isEqualTo(SiteHealth.ContentCheck.MISSING_MASTER);

        readiness.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED));
        assertThat(readiness.check(SITE))
                .as("an edit is reflected at once, without waiting out the TTL")
                .isEqualTo(SiteHealth.ContentCheck.OK);
    }
}
