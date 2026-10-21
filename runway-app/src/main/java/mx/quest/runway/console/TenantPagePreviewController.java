package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.head.HeadInjectionFilter;
import mx.quest.runway.page.LocaleUrls;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringValidator;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.page.PendingRevisionStore;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.web.DraftRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/**
 * The tenant page editor's <b>live preview</b> — a dedicated tenant-realm render that reuses the real page
 * pipeline (via {@link DraftRenderer}) but is deliberately isolated from the public {@code PageController}:
 *
 * <ul>
 *   <li>it renders <b>drafts</b> (loads by slug, so {@code published()} is bypassed) and, on the POST form,
 *       <b>unsaved</b> editor content;</li>
 *   <li>it skips the conditional-GET/ETag, locale redirects and preference cookies — a preview is a
 *       one-shot render, never a cacheable canonical response;</li>
 *   <li>it forces {@code noindex} (both the in-page {@link HeadInjectionFilter} context via {@code
 *       preview=true} and an explicit {@code X-Robots-Tag} header) and sends {@code Cache-Control:
 *       no-store} with no ETag;</li>
 *   <li>the editor embeds it in a same-origin {@code <iframe>} (framing is allowed by the tenant chain's
 *       {@code X-Frame-Options: SAMEORIGIN}).</li>
 * </ul>
 *
 * <p><b>Isolation.</b> The tenancy is taken only from the request binding ({@link SiteResolver#current()}),
 * never a path/query argument, and every route is gated by {@code @authz.may('EDIT_PAGES')} (the saved-page
 * preview and its window also admit {@code APPROVE_PAGES}, so a reviewer can see what they approve) — so one
 * tenant can never preview another's page, and a viewer without the grant is refused. Unsaved-draft preview
 * writes the posted markup to a <b>per-request-unique, non-routable scratch page</b> (the pipeline reads
 * templates from disk and has no source-string entry point), renders it, and deletes it in a {@code
 * finally} — unique per request so two editors/tabs on one tenancy never race on the name-keyed parse cache.
 */
@Controller
public class TenantPagePreviewController {

    private static final Logger log = LoggerFactory.getLogger(TenantPagePreviewController.class);

    private final SiteResolver siteResolver;
    private final PageStore pages;
    private final PageWriteService writer;
    private final RenderModelBuilder modelBuilder;
    private final DraftRenderer draftRenderer;
    private final MessageSource messages;
    private final AssetVersion assetVersion;

    public TenantPagePreviewController(SiteResolver siteResolver, PageStore pages, PageWriteService writer,
                                       RenderModelBuilder modelBuilder, DraftRenderer draftRenderer,
                                       @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                       AssetVersion assetVersion) {
        this.assetVersion = assetVersion;
        this.siteResolver = siteResolver;
        this.pages = pages;
        this.writer = writer;
        this.modelBuilder = modelBuilder;
        this.draftRenderer = draftRenderer;
        this.messages = messages;
    }

    /**
     * Previews a SAVED page (draft or published) by slug, in the bound tenancy — or, with {@code revision=1}, its
     * <b>pending revision</b> (falling back to the saved page when there is none). Open to {@code EDIT_PAGES}
     * (the editor's own preview) and to {@code APPROVE_PAGES}: an approver must be able to look at what they are
     * asked to approve without being granted authoring.
     */
    @GetMapping("/__admin/pages/{slug}/preview")
    @PreAuthorize("@authz.may('EDIT_PAGES') or @authz.may('APPROVE_PAGES')")
    @ResponseBody
    public ResponseEntity<String> previewSaved(@PathVariable String slug,
                                               @RequestParam(required = false) String locale,
                                               @RequestParam(required = false) String revision,
                                               HttpServletRequest request) {
        Site site = boundTenancy();
        Page page = pages.find(site.id(), slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such page"));
        if (revision != null && !revision.isBlank()) {
            java.util.Optional<PendingRevisionStore.Revision> rev = writer.pending().revision(site.id(), slug);
            if (rev.isPresent()) {
                return renderScratch(site, rev.get().page(), rev.get().html(), rev.get().thxml(), locale, request);
            }
        }
        return renderPreview(site, page, locale, request);
    }

    /**
     * Previews UNSAVED editor content (posted body + logic) against the page's saved metadata. The editor's live
     * preview ({@code page-editor.js}) submits its hidden form <em>into a sandboxed frame</em>, so the browser
     * navigates the frame to this response and this response's own {@code sandbox} CSP applies — never the
     * console page's policy, which a {@code srcdoc}/{@code blob:} rendering would inherit.
     */
    @PostMapping("/__admin/pages/{slug}/preview")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    @ResponseBody
    public ResponseEntity<String> previewDraft(@PathVariable String slug,
                                               @RequestParam String html,
                                               @RequestParam(required = false) String thxml,
                                               @RequestParam(required = false) String locale,
                                               HttpServletRequest request) {
        Site site = boundTenancy();
        Page base = pages.find(site.id(), slug).orElseGet(() -> minimalDraft(slug));
        // While a pending revision exists the editor shows it, so an unsaved preview carries ITS metadata.
        Page meta = writer.pending().revision(site.id(), slug).map(PendingRevisionStore.Revision::page).orElse(base);
        return renderScratch(site, meta, html, thxml, locale, request);
    }

    /**
     * Renders {@code html}/{@code thxml} under {@code base}'s metadata through a per-request scratch page, deleted
     * in a {@code finally}. Carries the page's real metadata (path, locales, title, master) so the preview reflects
     * it; only the slug (a throwaway) and draft flag differ. Always a draft, so it can never enter published routing.
     */
    private ResponseEntity<String> renderScratch(Site site, Page base, String html, String thxml, String locale,
                                                 HttpServletRequest request) {
        String scratchSlug = PageAuthoringValidator.SCRATCH_SLUG_PREFIX + UUID.randomUUID().toString().replace("-", "");
        Page scratch = new Page(scratchSlug, base.path(), base.localizedPaths(), base.locales(),
                base.title(), base.description(), base.activeNav(), base.template(), true,
                base.noindex(), base.canonical(), base.cacheControl(), base.params());
        try {
            writer.writeScratch(site.id(), scratch, html, thxml);
            return renderPreview(site, scratch, locale, request);
        } finally {
            writer.delete(site.id(), scratchSlug); // best-effort cleanup of the transient scratch page
        }
    }

    /**
     * The stand-alone <b>pop-out</b> preview window: a small, same-origin host page — clearly titled and badged
     * "Preview" so it can never be mistaken for the live site when switching windows — that frames the sandboxed
     * {@link #previewSaved saved-draft preview}. The editor opens THIS (not the raw preview) so the detached
     * window carries a Preview title/banner; it is re-pointed here after each save. The host itself runs no
     * scripts (its CSP forbids them); only the framed author content runs, still sandboxed in its opaque origin.
     */
    @GetMapping("/__admin/pages/{slug}/preview-window")
    @PreAuthorize("@authz.may('EDIT_PAGES') or @authz.may('APPROVE_PAGES')")
    @ResponseBody
    public ResponseEntity<String> previewWindow(@PathVariable String slug,
                                                @RequestParam(required = false) String revision) {
        Site site = boundTenancy();
        Page page = pages.find(site.id(), slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such page"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("X-Content-Type-Options", "nosniff")
                // The host is trusted, script-free console chrome; the framed author content stays sandboxed.
                .header("Content-Security-Policy",
                        "default-src 'self'; script-src 'none'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data:; frame-src 'self'")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(previewWindowDocument(site, page.slug(), revision != null && !revision.isBlank()));
    }

    /** A minimal, clearly-labelled host page framing the sandboxed preview, so a detached window is unmistakable. */
    private String previewWindowDocument(Site site, String slug, boolean revision) {
        Locale ui = LocaleContextHolder.getLocale();
        String badge = messages.getMessage("op.pages.edit.preview", null, "Preview", ui);
        String hint = messages.getMessage("op.pages.preview.window.hint", null,
                "Refreshes when you save in the editor", ui);
        String siteName = HtmlUtils.htmlEscape(site.name());
        String slugEsc = HtmlUtils.htmlEscape(slug);
        String badgeEsc = HtmlUtils.htmlEscape(badge);
        String src = HtmlUtils.htmlEscape("/__admin/pages/" + slug + "/preview" + (revision ? "?revision=1" : ""));
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\">"
                + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"" + assetVersion.url("/__assets/runway.svg") + "\">"
                + "<title>" + badgeEsc + " · " + slugEsc + " — " + siteName + "</title>"
                + "<style>html,body{margin:0;height:100%}"
                + "body{display:flex;flex-direction:column;background:#0f172a;"
                + "font-family:system-ui,-apple-system,'Segoe UI',sans-serif}"
                + ".pw-bar{flex:none;display:flex;align-items:center;gap:.55rem;padding:.5rem .85rem;"
                + "background:#1d283a;color:#e2e8f0;border-bottom:1px solid #334155;font-size:13px}"
                + ".pw-badge{background:#2563eb;color:#fff;font-weight:700;font-size:11px;letter-spacing:.08em;"
                + "text-transform:uppercase;padding:2px 8px;border-radius:6px}"
                + ".pw-site{font-weight:600}.pw-slug{color:#94a3b8}"
                + ".pw-hint{margin-left:auto;color:#94a3b8;font-size:12px}"
                + "iframe{flex:1;width:100%;border:0;background:#fff}</style></head><body>"
                + "<div class=\"pw-bar\"><span class=\"pw-badge\">" + badgeEsc + "</span>"
                + "<span class=\"pw-site\">" + siteName + "</span><span class=\"pw-slug\">/" + slugEsc + "</span>"
                + "<span class=\"pw-hint\">" + HtmlUtils.htmlEscape(hint) + "</span></div>"
                + "<iframe title=\"page preview\" sandbox=\"allow-scripts\" src=\"" + src + "\"></iframe>"
                + "</body></html>";
    }

    private ResponseEntity<String> renderPreview(Site site, Page page, String localeParam, HttpServletRequest request) {
        Locale locale = previewLocale(localeParam, page, site.defaultLocale());
        String body;
        try {
            // Force noindex on the in-page head-injection (read post-response at the edge); an empty baseUrl
            // keeps a preview's canonical links off the live host.
            request.setAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE,
                    modelBuilder.headContext(SiteViews.toView(site), page, locale, "", true));
            // Render the page, then anchor its relative URLs to the page's REAL public path. The preview is
            // served at a deeper URL (/__admin/pages/<slug>/preview) than the published page (/<path>), so a
            // relative asset URL in the content (e.g. "../static/img/x.jpg") would resolve against that deep
            // path and 404 — even though it resolves fine live. A <base href> pinned to the public path makes
            // the browser resolve every relative URL exactly as it will once published, so the preview is
            // faithful. Injected at the response layer, never in tenant templates (isolation law).
            body = withBaseHref(draftRenderer.render(site, page, locale, ""),
                    publicPath(page, locale, site.defaultLocale()));
        } catch (RuntimeException ex) {
            // A broken master/logic is the author's to fix — show the reason in the frame, not a 500 stack.
            log.warn("preview render failed for {}/{}", site.id(), page.slug(), ex);
            body = previewError(ex);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex, nofollow")
                // The preview is author-controlled HTML rendered at the tenant-console origin. Sandbox it so a
                // page's own scripts run in an OPAQUE origin (no allow-same-origin) — they can render, but can
                // never reach the parent editor DOM, its CSRF token, cookies, or same-origin authenticated
                // endpoints. A lower-privileged EDIT_PAGES author therefore cannot plant a script that escalates
                // when a tenant superadmin opens the editor. The response CSP `sandbox` applies even on direct
                // navigation; the editor iframe carries a matching sandbox attribute (defense in depth).
                .header("Content-Security-Policy", "sandbox allow-scripts")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(body);
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }

    /** Matches the opening {@code <head …>} tag (case-insensitive) so a {@code <base>} can be inserted first. */
    private static final Pattern HEAD_OPEN = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * The root-relative public URL path this page is served at in {@code locale} — the value a preview's
     * {@code <base href>} is pinned to. Uses the page's real routing path (honoring per-locale slug overrides via
     * {@link LocaleUrls}), <b>not</b> its {@code canonical} override (which may legitimately point off-site); a
     * page with no path yet (a brand-new draft) resolves against the site root.
     */
    private static String publicPath(Page page, Locale locale, Locale siteDefault) {
        String path = page.pathFor(locale);
        return (path == null || path.isBlank()) ? "/" : LocaleUrls.urlFor(path, locale, siteDefault);
    }

    /**
     * Inserts {@code <base href="…">} as the first child of {@code <head>} so every relative URL in the previewed
     * document resolves against the page's public path instead of the deeper preview endpoint URL. The href is
     * attribute-escaped. If the document has no {@code <head>} (a bare/standalone page) the tag is prepended — a
     * stray {@code <base>} is still hoisted into the head by the parser, so relative URLs are corrected either way.
     */
    private static String withBaseHref(String html, String baseHref) {
        if (html == null || baseHref == null || baseHref.isBlank()) {
            return html;
        }
        String tag = "<base href=\"" + HtmlUtils.htmlEscape(baseHref) + "\">";
        Matcher m = HEAD_OPEN.matcher(html);
        if (m.find()) {
            return html.substring(0, m.end()) + tag + html.substring(m.end());
        }
        return tag + html;
    }

    private static Locale previewLocale(String param, Page page, Locale siteDefault) {
        if (param != null && !param.isBlank()) {
            Locale requested = Locale.forLanguageTag(param);
            if (page.hasLocale(requested, siteDefault)) {
                return requested;
            }
        }
        return page.primaryLocale(siteDefault);
    }

    /** A minimal draft stand-in for previewing a page that has not been saved yet. */
    private static Page minimalDraft(String slug) {
        return new Page(slug, null, Map.of(), List.of(), Map.of(), Map.of(), "", "_master", true, false, null, null);
    }

    private static String previewError(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return "<!DOCTYPE html><html><head><meta name=\"robots\" content=\"noindex\"><title>Preview error</title></head>"
                + "<body style=\"font-family:system-ui,sans-serif;padding:1.5rem;color:#b00\">"
                + "<h1>Preview could not be rendered</h1><pre>" + HtmlUtils.htmlEscape(message)
                + "</pre></body></html>";
    }
}
