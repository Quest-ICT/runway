package mx.quest.runway.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringValidator;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.Severity;
import mx.quest.runway.web.DraftRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the quality checks over page content that is <b>not</b> (or not yet) the page's live files — an edit being
 * saved, a pending revision being approved — and decides what blocks publishing on a site that enforces quality.
 *
 * <p>The render pipeline reads templates from disk, so candidate content is written to a per-call, non-routable
 * preview <b>scratch</b> page ({@link PageAuthoringValidator#SCRATCH_SLUG_PREFIX}), rendered, and deleted in a
 * {@code finally} — the same mechanism the editor's unsaved-draft preview uses. The checks then inspect the
 * rendered markup against the candidate's metadata under its <b>real</b> slug, so a site-wide check (duplicate
 * title) compares the page with its siblings, not with a throwaway name.
 *
 * <p><b>What blocks:</b> only {@link Severity#ERROR} findings, plus a render that fails outright (a page that
 * cannot be rendered is broken, whatever the checks would have said). Warnings and infos never block. The gate is
 * run for <em>every</em> locale the page is offered in, because a title missing in one language is just as
 * missing there.
 */
@Component
public class QualityGate {

    private static final Logger log = LoggerFactory.getLogger(QualityGate.class);

    /** The code of the synthetic error reported when the candidate cannot be rendered at all. */
    public static final String RENDER_FAILED = "renderFailed";

    private final PageWriteService writer;
    private final DraftRenderer renderer;
    private final PageQualityService quality;

    public QualityGate(PageWriteService writer, DraftRenderer renderer, PageQualityService quality) {
        this.writer = writer;
        this.renderer = renderer;
        this.quality = quality;
    }

    /**
     * Renders {@code candidate}'s content ({@code html}/{@code thxml}) in {@code locale} and runs every check.
     * A render failure yields a report holding just the {@link #RENDER_FAILED} error.
     */
    public QualityReport inspect(Site site, Page candidate, String html, String thxml, Locale locale) {
        String rendered;
        try {
            rendered = render(site, candidate, html, thxml, locale);
        } catch (RuntimeException ex) {
            log.warn("quality render failed for {}/{}", site.id(), candidate.slug(), ex);
            return QualityReport.of(List.of(Finding.error(RENDER_FAILED, "the page could not be rendered")));
        }
        return quality.inspect(SiteViews.toView(site), candidate, locale, rendered);
    }

    /**
     * The findings that block publishing {@code candidate}: every {@code ERROR} across the page's locales, each
     * distinct (code, message) once, in the order first seen. Empty means "may publish".
     */
    public List<Finding> blocking(Site site, Page candidate, String html, String thxml) {
        Map<String, Finding> errors = new LinkedHashMap<>();
        for (Locale locale : locales(site, candidate)) {
            for (Finding finding : inspect(site, candidate, html, thxml, locale).ofSeverity(Severity.ERROR)) {
                errors.putIfAbsent(finding.code() + '|' + finding.message(), finding);
            }
        }
        return List.copyOf(errors.values());
    }

    /** The locales the page is offered in, the site default first when it is one of them. */
    private static List<Locale> locales(Site site, Page page) {
        if (page.locales().isEmpty()) {
            return List.of(site.defaultLocale());
        }
        List<Locale> ordered = new ArrayList<>(page.locales());
        if (ordered.remove(site.defaultLocale())) {
            ordered.add(0, site.defaultLocale());
        }
        return ordered;
    }

    private String render(Site site, Page candidate, String html, String thxml, Locale locale) {
        String scratchSlug = PageAuthoringValidator.SCRATCH_SLUG_PREFIX
                + UUID.randomUUID().toString().replace("-", "");
        // The candidate's own metadata under a throwaway, never-routable slug, always a draft.
        Page scratch = candidate.renamedTo(scratchSlug, candidate.path()).withDraft(true);
        try {
            writer.writeScratch(site.id(), scratch, html, thxml);
            return renderer.render(site, scratch, locale, "");
        } finally {
            writer.delete(site.id(), scratchSlug);
        }
    }
}
