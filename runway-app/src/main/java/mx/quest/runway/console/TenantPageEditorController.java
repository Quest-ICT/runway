package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import mx.quest.runway.authz.AuthzExpressions;
import mx.quest.runway.editorial.EditorialService;
import mx.quest.runway.editorial.PageStatus;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringException;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.page.PendingRevisionStore;
import mx.quest.runway.quality.QualityGate;
import mx.quest.runway.quality.PageQualityService;
import mx.quest.runway.quality.QualityReport;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteParamsService;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.web.DraftRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant page <b>editor</b> ({@code /__admin/pages/{slug}/edit}) — the authoring surface over the
 * read-only Pages list. It edits a page's body ({@code <slug>.html}), decoupled logic ({@code
 * <slug>.th.xml}) and metadata through {@link PageWriteService}, embeds the live preview, and shows the
 * advisory quality panel (recomputed on every load by rendering the draft server-side).
 *
 * <p>Gated by {@code @authz.may('EDIT_PAGES')} — a grantable tenant permission, so any granted user (not
 * only a superadmin) may author. <b>Visibility is a separate concern:</b> a save preserves the page's
 * current draft/published state, because flipping it is {@code DISABLE_PAGES} (the disable/enable actions),
 * not {@code EDIT_PAGES}. The tenancy is the bound one only ({@link SiteResolver#current()}), never a path
 * argument.
 *
 * <p><b>Editorial level.</b> What a save does is decided by {@link EditorialService} for the site's level: on an
 * enforced or reviewed site, saving a published page runs the quality gate and an {@code ERROR} refuses it — the
 * editor is then re-shown with the author's unsaved content and the blocking findings, so nothing typed is lost.
 * On a reviewed site, an editor without {@code APPROVE_PAGES} who saves a published page creates a pending
 * revision; while one exists, the editor opens the <em>revision</em> (not the live files) with a banner saying so,
 * and its preview and quality panel show the revision too.
 */
@Controller
public class TenantPageEditorController {

    private static final Logger log = LoggerFactory.getLogger(TenantPageEditorController.class);

    private final SiteResolver siteResolver;
    private final PageStore pages;
    private final PageWriteService writer;
    private final DraftRenderer draftRenderer;
    private final PageQualityService quality;
    private final mx.quest.runway.content.PathOwnership pathOwnership;
    private final MessageSource messages;
    private final SiteParamsService siteParams;
    private final EditorialService editorial;
    private final QualityGate gate;
    private final AuthzExpressions authz;

    public TenantPageEditorController(SiteResolver siteResolver, PageStore pages, PageWriteService writer,
                                      DraftRenderer draftRenderer, PageQualityService quality,
                                      mx.quest.runway.content.PathOwnership pathOwnership,
                                      @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                      SiteParamsService siteParams, EditorialService editorial,
                                      QualityGate gate, AuthzExpressions authz) {
        this.siteParams = siteParams;
        this.editorial = editorial;
        this.gate = gate;
        this.authz = authz;
        this.siteResolver = siteResolver;
        this.pages = pages;
        this.writer = writer;
        this.draftRenderer = draftRenderer;
        this.quality = quality;
        this.pathOwnership = pathOwnership;
        this.messages = messages;
    }

    /**
     * A localized quality finding for the panel: the severity (lower-cased, for the CSS class), a stable
     * {@code code} (the check's identity — used client-side to remember an "ignore" per page and to key the
     * Preview status flag; NOT localized and never shown), and the localized {@code message}.
     */
    public record FindingView(String severity, String code, String message) {
    }

    /**
     * One editable page-parameter row: the page's own {@code key}/{@code value}, and the site-level value for the
     * same key that this row overrides at render time ({@code null} when the site does not define the key).
     */
    public record ParamRow(String key, String value, String siteValue) {
    }

    /** The page's params in their order, each paired with the site value it overrides (null when none). */
    static List<ParamRow> paramRows(Map<String, String> pageParams, Map<String, String> siteParamMap) {
        List<ParamRow> rows = new ArrayList<>();
        pageParams.forEach((key, value) -> rows.add(new ParamRow(key, value, siteParamMap.get(key))));
        return rows;
    }

    /** The site params the page does NOT override, in the site's order — inherited unchanged by every render. */
    static List<Map.Entry<String, String>> inheritedParams(Map<String, String> pageParams,
                                                           Map<String, String> siteParamMap) {
        return siteParamMap.entrySet().stream().filter(e -> !pageParams.containsKey(e.getKey()))
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList();
    }

    @GetMapping("/__admin/pages/{slug}/edit")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String edit(@PathVariable String slug, Model model) {
        Site site = boundTenancy();
        Page live = pages.find(site.id(), slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such page"));
        // While a pending revision exists the editor works on IT, never on the live files: those stay untouched
        // until an approver acts, and an editor reopening the page must see their own unpublished changes.
        java.util.Optional<PendingRevisionStore.Revision> revision = writer.pending().revision(site.id(), slug);
        if (revision.isPresent()) {
            PendingRevisionStore.Revision rev = revision.get();
            Page page = rev.page().withDraft(live.draft());
            populate(model, site, live, page, rev.html(), rev.thxml() == null ? "" : rev.thxml(),
                    candidateFindings(site, page, rev.html(), rev.thxml()),
                    writer.pending().fingerprint(site.id(), slug));
        } else {
            String body = writer.readBody(site.id(), slug).orElse("");
            populate(model, site, live, live, body, writer.readLogic(site.id(), slug).orElse(""),
                    inspect(site, live, live.primaryLocale(site.defaultLocale())), "");
        }
        return "operator/op-layout";
    }

    /**
     * Fills the editor model for {@code page} (the live page, or its pending revision) with the given sources and
     * findings, plus the editorial state that decides which actions and banners the editor shows.
     *
     * @param live        the live page record (its status drives the badges and actions)
     * @param revisionKey the fingerprint of the pending revision being edited, or {@code ""} when editing the live
     *                    page — posted back on save so a direct save drops exactly the revision it absorbed
     */
    private void populate(Model model, Site site, Page live, Page page, String body, String logic,
                          List<FindingView> findings, String revisionKey) {
        String slug = live.slug();
        model.addAttribute("page", page);
        // The publication date renders into an <input type="date">, which only accepts yyyy-MM-dd — an Instant's
        // toString() would be rejected by the browser and the field would come back silently EMPTY on save,
        // wiping the date the author never touched. Formatted in UTC to match how PageForm parses it back, so
        // the value round-trips unchanged through an edit that does not touch this field.
        model.addAttribute("publishedOn", page.publishedAt() == null ? ""
                : java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                        .format(page.publishedAt().atZone(java.time.ZoneOffset.UTC)));
        model.addAttribute("body", body);
        model.addAttribute("logic", logic);
        model.addAttribute("titleMap", page.title());
        model.addAttribute("descMap", page.description());
        model.addAttribute("localizedPathMap", page.localizedPaths());
        model.addAttribute("siteLocales", site.locales());
        model.addAttribute("pageLocaleCodes", page.locales().stream().map(Locale::getLanguage).toList());
        // The page's own template parameters, as an ordered row list the editor iterates into key/value inputs
        // (a trailing blank row in the template lets the author add one), each carrying the site-level value it
        // overrides. Site parameters are edited on the Site config screen (/__admin/config); the ones this page
        // does not override are listed read-only, so the editor shows the EFFECTIVE set a render sees.
        Map<String, String> siteParamMap = siteParams.get(site.id());
        model.addAttribute("paramEntries", paramRows(page.params(), siteParamMap));
        model.addAttribute("inheritedParams", inheritedParams(page.params(), siteParamMap));
        boolean onRevision = !revisionKey.isEmpty();
        model.addAttribute("previewUrl", "/__admin/pages/" + slug + "/preview" + (onRevision ? "?revision=1" : ""));
        model.addAttribute("previewWindowUrl",
                "/__admin/pages/" + slug + "/preview-window" + (onRevision ? "?revision=1" : ""));
        // The live preview of UNSAVED edits (page-editor.js) POSTs the editors' contents here; while a pending
        // revision exists the endpoint renders them under the revision's settings, so no ?revision flag is needed.
        model.addAttribute("livePreviewUrl", "/__admin/pages/" + slug + "/preview");
        model.addAttribute("findings", findings);
        if (!model.containsAttribute("blocked")) {
            model.addAttribute("blocked", List.of());
        }

        // Editorial state: which banner and which actions (Publish / Submit for review / Withdraw) to offer.
        boolean mayApprove = authz.may("APPROVE_PAGES");
        PageStatus status = editorial.status(site.id(), live);
        model.addAttribute("status", status);
        model.addAttribute("review", status.review());
        model.addAttribute("reviewSubmittedAt", when(status.review() == null ? null : status.review().submittedAt()));
        model.addAttribute("reviewReturnedAt", when(status.review() == null ? null : status.review().returnedAt()));
        model.addAttribute("editorialLevel", site.editorial().key());
        model.addAttribute("revisionKey", revisionKey);
        model.addAttribute("editingRevision", onRevision);
        model.addAttribute("savesAsRevision", !editorial.publishesDirectly(site, live, mayApprove));
        model.addAttribute("canPublish", live.draft() && editorial.mayPublish(site, mayApprove)
                && authz.may("DISABLE_PAGES"));
        model.addAttribute("canSubmit", site.editorial().requiresReview() && !status.awaitingReview()
                && (live.draft() || status.hasRevision()));
        model.addAttribute("canWithdraw", status.review() != null || status.hasRevision());
        model.addAttribute("mayApprove", mayApprove);

        model.addAttribute("pageTitleKey", "op.pages.edit.title");
        model.addAttribute("contentTemplate", "operator/admin/page-editor");
        model.addAttribute("contentScripts", "operator/admin/page-editor");
    }

    @PostMapping("/__admin/pages/{slug}")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String save(@PathVariable String slug, @ModelAttribute PageForm form,
                       @RequestParam(name = "revisionKey", required = false) String revisionKey,
                       Model model, RedirectAttributes redirect) {
        Site site = boundTenancy();
        Page existing = pages.find(site.id(), slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such page"));
        // A save keeps the current draft/published state — changing visibility is DISABLE_PAGES, not this.
        Page candidate = form.toPage(slug, existing.draft());
        try {
            EditorialService.SaveResult result = editorial.save(site, candidate, form.getHtml(), form.getThxml(),
                    authz.may("APPROVE_PAGES"), revisionKey);
            switch (result.outcome()) {
                case BLOCKED -> {
                    // Re-show the editor with exactly what the author typed (a redirect would reload the stored
                    // page and lose the edit) and the findings that blocked it.
                    model.addAttribute("blocked", localize(result.blocking()));
                    String thxml = form.getThxml() == null ? "" : form.getThxml();
                    populate(model, site, existing, candidate, form.getHtml(), thxml,
                            candidateFindings(site, candidate, form.getHtml(), form.getThxml()),
                            revisionKey == null ? "" : revisionKey);
                    model.addAttribute("error", messages.getMessage("op.review.blocked.save", null,
                            "Not saved: the page has quality errors.", LocaleContextHolder.getLocale()));
                    return "operator/op-layout";
                }
                case SAVED_AS_REVISION -> redirect.addFlashAttribute("flash", messages.getMessage(
                        "op.review.savedAsRevision", null,
                        "Saved as a pending revision. The live page is unchanged until it is approved.",
                        LocaleContextHolder.getLocale()));
                default -> redirect.addFlashAttribute("flash", messages.getMessage("op.pages.edit.saved", null,
                        "Saved.", LocaleContextHolder.getLocale()));
            }
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", messages.getMessage("op.pages.error." + ex.code(), null,
                    ex.getMessage(), LocaleContextHolder.getLocale()));
        } catch (mx.quest.runway.editorial.EditorialException ex) {
            redirect.addFlashAttribute("error", messages.getMessage("op.review.error." + ex.code(), null,
                    ex.getMessage(), LocaleContextHolder.getLocale()));
        }
        return "redirect:/__admin/pages/" + slug + "/edit";
    }

    /** An instant as {@code yyyy-MM-dd HH:mm} in the server's zone (blank when unknown) — for the review banner. */
    static String when(java.time.Instant instant) {
        return instant == null ? "" : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(instant.atZone(java.time.ZoneId.systemDefault()));
    }

    /** Localized views of findings (no path advisories) — for the panel and the "why it was blocked" list. */
    private List<FindingView> localize(List<Finding> findings) {
        Locale ui = LocaleContextHolder.getLocale();
        List<FindingView> views = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            views.add(new FindingView(finding.severity().name().toLowerCase(Locale.ROOT), finding.code(),
                    messages.getMessage("quality." + finding.code(), null, finding.message(), ui)));
        }
        return views;
    }

    /**
     * The quality panel for content that is not the page's live files (a pending revision, or an edit that was
     * refused): rendered through the {@link QualityGate}'s scratch page, then the same path advisories as
     * {@link #inspect}.
     */
    private List<FindingView> candidateFindings(Site site, Page page, String html, String thxml) {
        Locale pageLocale = page.primaryLocale(site.defaultLocale());
        QualityReport report = gate.inspect(site, page, html, thxml, pageLocale);
        List<FindingView> views = new ArrayList<>(localize(report.findings()));
        views.addAll(pathAdvisories(site, page));
        return views;
    }

    /** Renders the draft server-side and runs the advisory checks, returning localized findings for the panel. */
    private List<FindingView> inspect(Site site, Page page, Locale pageLocale) {
        List<FindingView> views = new ArrayList<>();
        String resolvedHtml = "";
        try {
            resolvedHtml = draftRenderer.render(site, page, pageLocale, "");
        } catch (RuntimeException ex) {
            // A broken master/logic is itself worth surfacing — as a finding, not a 500.
            log.warn("quality render failed for {}/{}", site.id(), page.slug(), ex);
            views.add(new FindingView("error", "renderFailed", messages.getMessage("op.pages.edit.renderFailed",
                    null, "The page could not be rendered.", LocaleContextHolder.getLocale())));
        }
        QualityReport report = quality.inspect(SiteViews.toView(site), page, pageLocale, resolvedHtml);
        views.addAll(localize(report.findings()));
        views.addAll(pathAdvisories(site, page));
        return views;
    }

    /**
     * The six URL-path advisories. They stay here, in the controller, rather than becoming SPI
     * {@code PageQualityCheck}s: each needs the page's raw {@code path}/{@code localizedPaths} for EVERY locale (and
     * one needs the content-source path claims), which the frozen {@code PageView} boundary does not expose — it
     * carries only the canonical URL of the one locale being inspected. A kernel check that re-read the page from
     * the store to get them would inspect the SAVED page, not the edit or revision under review, and so report on
     * the wrong content. They are advisories (warning/info), so they never take part in enforcement either way.
     */
    private List<FindingView> pathAdvisories(Site site, Page page) {
        List<FindingView> views = new ArrayList<>();
        Locale ui = LocaleContextHolder.getLocale();

        // Kernel-computed i18n/URL advisory: on a multilingual site whose page URLs are not localized, the
        // unprefixed default-locale URL keeps whatever slug it was authored with — so after the default locale
        // is changed the slug no longer matches the content language (e.g. a Spanish page served at "/about").
        // This is NOT an SPI quality check because it needs the page's raw path/localizedPaths, which the frozen
        // PageView boundary deliberately does not expose.
        if (urlsNotLocalized(page, site)) {
            views.add(new FindingView("info", "i18nPath", messages.getMessage("op.pages.quality.i18nPath",
                    new Object[]{page.path()},
                    "URLs are not localized — every language uses the same path. Set a per-locale Path (below) so "
                            + "each URL matches its content language.", ui)));
        }

        // Underscore-in-path advisory: hyphens are the URL-slug word separator (search engines split on '-' but
        // read '_' as a word joiner), so an underscore in a path is a bad practice worth flagging.
        String underscored = underscoreInAnyPath(page);
        if (underscored != null) {
            views.add(new FindingView("warning", "underscorePath", messages.getMessage(
                    "op.pages.quality.underscorePath",
                    new Object[]{underscored}, "The URL path uses underscores; prefer hyphens (-).", ui)));
        }

        // Improper-characters advisory: a clean URL path is letters, digits and hyphens; a space or a symbol
        // (? # % & …) has to be percent-encoded. Because Runway routes paths by an EXACT, case-sensitive match
        // (PageStore.findByPath) that never decodes, such a path is fragile and often unreachable at the URL a
        // visitor would type. Non-ASCII LETTERS are handled by the softer, separate advisory below — they are
        // valid (if encoded), so they get a nuanced message, not this "characters that don't belong" one.
        String improper = improperCharsInAnyPath(page);
        if (improper != null) {
            views.add(new FindingView("warning", "improperCharsPath", messages.getMessage(
                    "op.pages.quality.improperCharsPath",
                    new Object[]{improper}, "The URL path has characters that must be percent-encoded; use "
                            + "lowercase letters, numbers and hyphens (-).", ui)));
        }

        // Non-ASCII-letters advisory (softer than "improper"): an accented/other-script LETTER (é, ñ, ü, …) is a
        // VALID URL character but is percent-encoded in links (/niños → /ni%C3%B1os), which looks messy and can
        // break in older tooling. It is acceptable — even natural — for a slug aimed at that language's readers,
        // so the message explains the trade-off rather than telling the author to strip it. Kept distinct from
        // the improper (spaces/symbols) advisory precisely so each can give the right advice.
        String nonAscii = nonAsciiLetterInAnyPath(page);
        if (nonAscii != null) {
            views.add(new FindingView("warning", "nonAsciiPath", messages.getMessage(
                    "op.pages.quality.nonAsciiPath",
                    new Object[]{nonAscii}, "The URL path uses non-ASCII letters; these work but are "
                            + "percent-encoded in links. A plain-ASCII slug is more portable.", ui)));
        }

        // Shadowed-content advisory (the visibility triple's page-editor leg):
        // a REAL page always wins a contested URL, so if a content source (a blog entry, any plugin content)
        // also serves one of this page's paths, that content silently becomes unreachable. Say so here, where
        // the page author is looking — the conflicts panel on the Pages screen shows the same fact site-wide.
        java.util.LinkedHashSet<Locale> advisoryLocales = new java.util.LinkedHashSet<>();
        advisoryLocales.add(site.defaultLocale()); // the always-served URL space first (mirrors the router)
        advisoryLocales.addAll(site.locales());
        // ONE contributed-enumeration pass; each locale is then a map probe (not a re-enumeration).
        java.util.Map<String, List<String>> claimsByPath = pathOwnership.claimsByPath(SiteViews.toView(site));
        for (Locale siteLocale : advisoryLocales) {
            String pagePath = page.pathFor(siteLocale);
            List<String> claims = claimsByPath.getOrDefault(siteLocale.getLanguage() + '|' + pagePath, List.of());
            if (!claims.isEmpty()) {
                views.add(new FindingView("warning", "pathShadowsContent", messages.getMessage(
                        "op.pages.quality.pathShadowsContent",
                        new Object[]{pagePath, String.join(", ", claims)},
                        "This path is also served by plugin content ({1}). The page wins - that content is "
                                + "unreachable at {0} while this page keeps the path.", ui)));
                break; // one advisory is enough; the conflicts panel lists every locale's collision
            }
        }

        // Uppercase advisory: URL paths are case-sensitive (RFC 3986) and Runway matches them exactly, so an
        // uppercase slug serves only at that exact casing — "/About" and "/about" are different addresses, a
        // broken-link / duplicate-URL risk. Prefer all-lowercase.
        String uppercased = uppercaseInAnyPath(page);
        if (uppercased != null) {
            views.add(new FindingView("warning", "uppercasePath", messages.getMessage(
                    "op.pages.quality.uppercasePath",
                    new Object[]{uppercased}, "The URL path has uppercase letters; URLs are case-sensitive, so "
                            + "prefer all-lowercase.", ui)));
        }
        return views;
    }

    /**
     * The first of the page's URL paths (its default {@link Page#path()} or any per-locale override) that uses
     * an underscore, or {@code null} if none — hyphens are the URL-slug word separator; underscores are a slug
     * bad practice. Package-private and pure for unit testing.
     */
    static String underscoreInAnyPath(Page page) {
        if (page.path() != null && page.path().indexOf('_') >= 0) {
            return page.path();
        }
        for (String localized : page.localizedPaths().values()) {
            if (localized != null && localized.indexOf('_') >= 0) {
                return localized;
            }
        }
        return null;
    }

    /**
     * A character that does NOT belong in a clean URL path: anything other than a letter (of ANY script —
     * {@code \p{L}}, so an accented localized slug like {@code /café} is NOT flagged), an ASCII digit, or one
     * of the URL-safe path punctuation marks {@code - . _ ~ /}. Matches spaces and symbols ({@code ? # % & +}
     * …), which must be percent-encoded. ({@code _} is allowed here because underscores have their own,
     * softer, best-practice advisory; uppercase letters likewise — they are letters, flagged separately.)
     */
    private static final Pattern IMPROPER_PATH_CHAR = Pattern.compile("[^\\p{L}0-9._~/-]");

    /**
     * The first of the page's URL paths (its default {@link Page#path()} or any per-locale override) that
     * contains a character that does not belong in a clean URL (see {@link #IMPROPER_PATH_CHAR}), or {@code
     * null} if none. Such characters must be percent-encoded, which makes links fragile and — because Runway
     * routes paths by an exact, undecoded, case-sensitive match — can leave the page unreachable at the URL a
     * visitor would type. Package-private and pure for unit testing.
     */
    static String improperCharsInAnyPath(Page page) {
        if (hasImproperChar(page.path())) {
            return page.path();
        }
        for (String localized : page.localizedPaths().values()) {
            if (hasImproperChar(localized)) {
                return localized;
            }
        }
        return null;
    }

    private static boolean hasImproperChar(String path) {
        return path != null && IMPROPER_PATH_CHAR.matcher(path).find();
    }

    /**
     * The first of the page's URL paths (its default {@link Page#path()} or any per-locale override) that
     * contains a non-ASCII letter (an accented or other-script letter such as {@code é ñ ü}), or {@code null}
     * if none. Such letters are valid URL characters but are percent-encoded in links; this is a softer
     * advisory than {@link #improperCharsInAnyPath} (which is about characters that must never appear), and it
     * matches only LETTERS — a non-ASCII symbol/emoji is not a letter and is caught by the improper advisory
     * instead, so the two never double-report the same character. Package-private and pure for unit testing.
     */
    static String nonAsciiLetterInAnyPath(Page page) {
        if (hasNonAsciiLetter(page.path())) {
            return page.path();
        }
        for (String localized : page.localizedPaths().values()) {
            if (hasNonAsciiLetter(localized)) {
                return localized;
            }
        }
        return null;
    }

    /** Any letter outside the ASCII range, by code point (accented/other-script letters; not symbols/emoji). */
    private static boolean hasNonAsciiLetter(String path) {
        return path != null && path.codePoints().anyMatch(cp -> cp > 0x7F && Character.isLetter(cp));
    }

    /**
     * The first of the page's URL paths (its default {@link Page#path()} or any per-locale override) that
     * contains an uppercase letter of ANY script (so {@code /Éxito} is flagged, not only {@code /Exito}), or
     * {@code null} if none. URL paths are case-sensitive (RFC 3986) and Runway matches them with an exact
     * {@code String.equals} ({@code PageStore.findByPath}), so an uppercase slug serves only at that exact
     * casing — a broken-link / duplicate-URL risk that is independent of the letter's script. Package-private
     * and pure for unit testing.
     */
    static String uppercaseInAnyPath(Page page) {
        if (hasUppercase(page.path())) {
            return page.path();
        }
        for (String localized : page.localizedPaths().values()) {
            if (hasUppercase(localized)) {
                return localized;
            }
        }
        return null;
    }

    /** Any uppercase letter, by code point (so supplementary and non-ASCII uppercase letters count too). */
    private static boolean hasUppercase(String path) {
        return path != null && path.codePoints().anyMatch(Character::isUpperCase);
    }

    /**
     * Whether this page — <b>offered in more than one language</b> — uses one URL path for every locale (no
     * per-locale slug override): the standing condition that lets a default-locale change strand a page at a
     * slug in the wrong language. Scoped to the page's own locales (a page offered in a single language has no
     * other-locale URL to mismatch, even on a multilingual site). Package-private and pure for unit testing.
     */
    static boolean urlsNotLocalized(Page page, Site site) {
        // The locales this page is actually offered in (an empty declaration means site-default only).
        List<Locale> pageLocales = page.locales().isEmpty() ? List.of(site.defaultLocale()) : page.locales();
        if (pageLocales.size() <= 1) {
            return false; // offered in a single language: nothing to localize
        }
        String defaultPath = page.pathFor(site.defaultLocale());
        return pageLocales.stream()
                .filter(loc -> !loc.equals(site.defaultLocale()))
                .allMatch(loc -> page.pathFor(loc).equals(defaultPath));
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }
}
