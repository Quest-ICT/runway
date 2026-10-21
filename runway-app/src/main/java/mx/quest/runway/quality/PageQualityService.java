package mx.quest.runway.quality;

import java.util.Locale;
import mx.quest.runway.page.LocaleUrls;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageViews;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.SiteView;
import org.springframework.stereotype.Service;

/**
 * Runs the advisory content/SEO quality checks over a page draft and returns a grouped {@link QualityReport}
 * for the editor's quality panel. It materializes the host boundary {@link PageView} the checks consume — a
 * {@link SiteView}, the page's {@link PageMeta} for the locale, its canonical URL, and the freshly-rendered
 * draft markup ({@code resolvedHtml}, so a check can examine the actual delivered HTML, not just the
 * metadata) — and delegates to the same {@link PageQualityCheckRegistry} that collects the kernel's built-in
 * checks and any plugin-contributed ones (no two-class system).
 *
 * <p><b>Advisory by contract:</b> this never edits the page and never blocks a save or publish — it only
 * reports. The caller renders the draft (reusing the live-preview pipeline) and passes the HTML in, so this
 * service stays a pure function of its inputs (no render/DB dependency) and is trivially unit-testable.
 */
@Service
public class PageQualityService {

    private final PageQualityCheckRegistry registry;

    public PageQualityService(PageQualityCheckRegistry registry) {
        this.registry = registry;
    }

    /**
     * Inspects {@code page} as rendered ({@code resolvedHtml}) in {@code locale} and returns the findings
     * grouped by severity. An empty report means nothing was flagged (or no checks are installed).
     *
     * @param site         the resolved site view
     * @param page         the page being edited (draft or published)
     * @param locale       the locale the draft was rendered in
     * @param resolvedHtml the freshly-rendered draft markup ({@code null} tolerated → empty)
     */
    public QualityReport inspect(SiteView site, Page page, Locale locale, String resolvedHtml) {
        Locale siteDefault = site.defaultLocale();
        PageMeta meta = PageViews.toView(page, locale, siteDefault);
        String canonical = (site.baseUrl() == null ? "" : site.baseUrl())
                + LocaleUrls.urlFor(page, locale, siteDefault);
        PageView view = new PageView(site, meta, locale, canonical, resolvedHtml);
        return QualityReport.of(registry.inspect(view));
    }
}
