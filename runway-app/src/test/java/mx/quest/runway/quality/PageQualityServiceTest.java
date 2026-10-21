package mx.quest.runway.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import mx.quest.runway.page.Page;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.Severity;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/**
 * The advisory quality service: it materializes the {@link PageView} the checks consume (site + page
 * meta + canonical + rendered draft) and returns the findings grouped by severity, never blocking or editing.
 */
class PageQualityServiceTest {

    private static SiteView site() {
        return SiteViews.toView(new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
    }

    private static Page page(Map<String, String> title) {
        return new Page("home", "/", Map.of(), List.of(), title, Map.of(), "", "_master", false, false, null, null);
    }

    private static PageQualityCheck check(String key, Function<PageView, List<Finding>> body) {
        return new PageQualityCheck() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public List<Finding> inspect(PageView page) {
                return body.apply(page);
            }
        };
    }

    private static PageQualityService serviceWith(PageQualityCheck... checks) {
        return new PageQualityService(new PageQualityCheckRegistry(List.of(checks)));
    }

    @Test
    void aTitleLessPageYieldsTheTitleMissingError() {
        QualityReport report = new PageQualityService(new PageQualityCheckRegistry(List.of(new TitlePresenceCheck())))
                .inspect(site(), page(Map.of()), Locale.ENGLISH, "<html></html>");
        assertEquals(1, report.findings().size());
        assertEquals("title.missing", report.findings().get(0).code());
        // An error: a page without a title is broken, and an error is what an enforced site blocks on.
        assertEquals(Severity.ERROR, report.findings().get(0).severity());
        assertEquals(1, report.errors());
        assertTrue(report.hasFindings());
    }

    @Test
    void aTitledPageIsClean() {
        QualityReport report = new PageQualityService(new PageQualityCheckRegistry(List.of(new TitlePresenceCheck())))
                .inspect(site(), page(Map.of("en", "Welcome")), Locale.ENGLISH, "<html></html>");
        assertTrue(report.isEmpty());
    }

    @Test
    void findingsAreGroupedBySeverityMostSevereFirstStably() {
        PageQualityService service = serviceWith(check("mixed", pv -> List.of(
                Finding.info("i1", "note"), Finding.error("e1", "boom"),
                Finding.warning("w1", "warn"), Finding.info("i2", "note2"))));
        List<Finding> findings = service.inspect(site(), page(Map.of("en", "T")), Locale.ENGLISH, "").findings();
        // ERROR, then WARNING, then INFO; the two INFOs keep their registry order (stable sort).
        assertEquals(List.of("e1", "w1", "i1", "i2"), findings.stream().map(Finding::code).toList());
    }

    @Test
    void theRenderedHtmlAndCanonicalReachTheChecks() {
        AtomicReference<PageView> captured = new AtomicReference<>();
        PageQualityService service = serviceWith(check("capture", pv -> {
            captured.set(pv);
            return List.of();
        }));
        service.inspect(site(), page(Map.of("en", "T")), Locale.ENGLISH, "<h1>Hi</h1>");
        assertEquals("<h1>Hi</h1>", captured.get().resolvedHtml(), "the freshly-rendered markup is passed through");
        // The site view derives an absolute base URL from its host when none is configured.
        assertEquals("https://acme.test/", captured.get().canonical(), "canonical is the page's absolute URL");
    }

    @Test
    void anEmptyRegistryYieldsAnEmptyReport() {
        assertTrue(serviceWith().inspect(site(), page(Map.of()), Locale.ENGLISH, "").isEmpty());
    }
}
