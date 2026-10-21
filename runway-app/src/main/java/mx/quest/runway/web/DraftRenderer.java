package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.console.OperatorAwareMessageSource;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Renders a tenant page to HTML through the real page pipeline — the shared core behind both the editor's
 * <b>live preview</b> and its <b>advisory quality</b> run. It assembles the same model as {@code
 * PageController} (render model + content widgets + by-id link map) and renders the site-scoped template,
 * evicting the page's parsed-template entry first so a just-saved draft is never served stale.
 *
 * <p>Unlike {@code PageController} it does <b>not</b> apply the conditional-GET/ETag, locale redirects, or
 * cookies, and it does not stash the edge head-injection context — those are the caller's concern (the
 * preview controller forces {@code noindex}; the quality run needs only the body). It renders drafts as
 * readily as published pages (the caller loads the page by slug), so an author sees unpublished work.
 *
 * <p>Its third caller is the <b>search index</b> ({@link #renderForIndex}): a page's indexable text is what a
 * visitor reads, which includes every string the site's bundles supply through decoupled logic
 * ({@code th:text="#{key}"} in the {@code .th.xml}) — text the template file alone does not contain.
 */
@Component
public class DraftRenderer {

    private final RenderModelBuilder modelBuilder;
    private final WidgetModelBuilder widgetModelBuilder;
    private final PageRouting routing;
    private final mx.quest.runway.content.ContentCatalog contentCatalog;
    private final PageRenderer renderer;
    private final TemplateCacheService templateCache;
    private final SiteResolver siteResolver;

    public DraftRenderer(RenderModelBuilder modelBuilder, WidgetModelBuilder widgetModelBuilder,
                         PageRouting routing, mx.quest.runway.content.ContentCatalog contentCatalog,
                         PageRenderer renderer, TemplateCacheService templateCache, SiteResolver siteResolver) {
        this.modelBuilder = modelBuilder;
        this.widgetModelBuilder = widgetModelBuilder;
        this.routing = routing;
        this.contentCatalog = contentCatalog;
        this.renderer = renderer;
        this.templateCache = templateCache;
        this.siteResolver = siteResolver;
    }

    /**
     * Renders {@code page} in {@code locale} to HTML.
     *
     * @param site    the resolved (bound) site
     * @param page    the page to render (draft or published)
     * @param locale  the locale to render in
     * @param baseUrl prepended to canonical/alternate URLs (a preview passes {@code ""} so links don't point
     *                at the live host)
     */
    public String render(Site site, Page page, Locale locale, String baseUrl) {
        return render(site, page, locale, baseUrl, true);
    }

    /**
     * Renders a <b>published</b> page in {@code locale} for the search index: the same pipeline as a preview,
     * but it keeps the parsed-template cache (a reindex renders every page of a site, and evicting each one would
     * make live traffic re-parse them all) and links are relative ({@code baseUrl} {@code ""}).
     *
     * <p>It works with or without a request. The site's {@code #{}} strings resolve against the site bound to
     * the current thread, so the render binds {@code site} for its own duration and restores whatever was bound
     * before — a reindex can run on a console request, a request for another host, or no request at all.
     *
     * @param site   the site the page belongs to
     * @param page   the page (the caller passes a published one)
     * @param locale the locale whose strings the render resolves
     * @return the rendered HTML
     */
    public String renderForIndex(Site site, Page page, Locale locale) {
        Site prior = siteResolver.current().orElse(null);
        siteResolver.bind(site);
        try {
            return render(site, page, locale, "", false);
        } finally {
            if (prior == null) {
                siteResolver.clear();
            } else {
                siteResolver.bind(prior);
            }
        }
    }

    private String render(Site site, Page page, Locale locale, String baseUrl, boolean evictFirst) {
        // This renders the TENANT's own page (its #{} keys) even though the caller (editor preview / quality) is
        // served from a /__admin console URL — force site-string resolution so OperatorAwareMessageSource does
        // not hand it the console's operator strings (which would echo every site key as its bare code). Scope it
        // to THIS render only and restore afterward: the editor's quality panel calls this mid-request and then
        // renders its own console chrome (operator strings) on the same request, which must be unaffected.
        HttpServletRequest request =
                RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
        Object priorFlag = request == null ? null : request.getAttribute(OperatorAwareMessageSource.SITE_MESSAGES_ATTRIBUTE);
        if (request != null) {
            request.setAttribute(OperatorAwareMessageSource.SITE_MESSAGES_ATTRIBUTE, Boolean.TRUE);
        }
        try {
            SiteView siteView = SiteViews.toView(site);
            String masterName = SiteTemplateResolver.PREFIX + site.id() + "/" + page.template();
            Map<String, Object> model =
                    modelBuilder.build(siteView, page, locale, masterName, "/" + page.slug(), baseUrl);
            // A draft preview has no request query — a query-aware widget renders its neutral (no-query) form.
            model.put("widgets", widgetModelBuilder.build(site.id(), page.slug(), page.template(),
                    siteView, (PageMeta) model.get("page"), locale, Map.of()));
            // Same urls map the live render gets — pages by slug PLUS source content by <sourceKey>:<id> —
            // so a page linking a blog entry previews with the link resolved, not null.
            java.util.Map<String, String> urls = new java.util.HashMap<>(routing.urlMap(site, locale));
            urls.putAll(contentCatalog.urlMap(siteView, locale));
            model.put("urls", urls);
            if (evictFirst) {
                templateCache.evictPage(site.id(), page.slug()); // never serve a stale draft parse
            }
            return renderer.render(site.id(), SiteTemplateResolver.PREFIX + site.id() + "/" + page.slug(), locale, model);
        } finally {
            if (request != null) {
                if (priorFlag == null) {
                    request.removeAttribute(OperatorAwareMessageSource.SITE_MESSAGES_ATTRIBUTE);
                } else {
                    request.setAttribute(OperatorAwareMessageSource.SITE_MESSAGES_ATTRIBUTE, priorFlag);
                }
            }
        }
    }
}
