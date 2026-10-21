package mx.quest.runway.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.content.ContentRouter;
import mx.quest.runway.content.RoutableContent;
import mx.quest.runway.head.HeadInjectionFilter;
import mx.quest.runway.page.LocaleNegotiator;
import mx.quest.runway.page.LocaleUrls;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageValidator;
import mx.quest.runway.page.PageValidatorService;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.page.Routable;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves a public tenant page (page-as-entry) under the <b>default-unprefixed</b> locale scheme:
 *
 * <ul>
 *   <li>{@code ?lang=X} (the switcher) sets the per-site preference cookie and 302-redirects to the
 *       page's URL in X;</li>
 *   <li>{@code /<non-default>/path} serves that locale (404 if the page lacks it);</li>
 *   <li>{@code /<default>/path} is never canonical → 301 to the unprefixed form;</li>
 *   <li>an unprefixed path negotiates the locale (cookie → {@code Accept-Language} → site default →
 *       page primary): if it lands on the default it serves in place; otherwise it 302-redirects to
 *       the prefixed canonical URL.</li>
 * </ul>
 *
 * <p>No resolved site, a reserved path, or no matching published page yields {@code 404}. (Catch-all
 * mapping; static-asset coexistence is wired when assets land.)
 */
@Controller
public class PageController {

    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    /**
     * The {@code Cache-Control} of a query-dependent render (see {@code validated}): only the visitor's browser may
     * keep it, and only to show it again after asking — which, with no validator, is a fresh render.
     */
    static final String QUERY_RENDER_CACHE_CONTROL = "private, no-cache";

    private final SiteResolver siteResolver;
    private final PageRouting routing;
    private final ContentRouter contentRouter;
    private final ContentCatalog contentCatalog;
    private final RenderModelBuilder modelBuilder;
    private final WidgetModelBuilder widgetModelBuilder;
    private final PageValidatorService pageValidator;
    private final PageRenderer renderer;
    private final ErrorPages errorPages;
    private final RunwayHttpCacheProperties httpCache;
    /** The public-page CSP nonce source; {@code null} in tests that don't wire it — then inert. */
    private final PublicPageNonce publicNonce;

    /** Form without the public-page nonce (tests); production uses the canonical constructor below. */
    public PageController(SiteResolver siteResolver, PageRouting routing, ContentRouter contentRouter,
                          ContentCatalog contentCatalog, RenderModelBuilder modelBuilder,
                          WidgetModelBuilder widgetModelBuilder, PageValidatorService pageValidator,
                          PageRenderer renderer, ErrorPages errorPages, RunwayHttpCacheProperties httpCache) {
        this(siteResolver, routing, contentRouter, contentCatalog, modelBuilder, widgetModelBuilder, pageValidator,
                renderer, errorPages, httpCache, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PageController(SiteResolver siteResolver, PageRouting routing, ContentRouter contentRouter,
                          ContentCatalog contentCatalog, RenderModelBuilder modelBuilder,
                          WidgetModelBuilder widgetModelBuilder, PageValidatorService pageValidator,
                          PageRenderer renderer, ErrorPages errorPages, RunwayHttpCacheProperties httpCache,
                          PublicPageNonce publicNonce) {
        this.publicNonce = publicNonce;
        this.siteResolver = siteResolver;
        this.routing = routing;
        this.contentRouter = contentRouter;
        this.contentCatalog = contentCatalog;
        this.modelBuilder = modelBuilder;
        this.widgetModelBuilder = widgetModelBuilder;
        this.pageValidator = pageValidator;
        this.renderer = renderer;
        this.errorPages = errorPages;
        this.httpCache = httpCache;
    }

    @GetMapping("/**")
    @ResponseBody
    public ResponseEntity<String> serve(HttpServletRequest request) {
        Site site = siteResolver.current().orElse(null);
        String path = request.getRequestURI();
        if (site == null) {
            // No tenant matched the host — there is no site to compose an error page from.
            return errorPages.render(null, Locale.ENGLISH, HttpStatus.NOT_FOUND);
        }
        Locale siteDefault = site.defaultLocale();
        try {
            // Reserved if either spelling is (RequestPaths): /%5F%5Fx is reserved like /__x.
            if (RequestPaths.eitherMatches(request, PageController::isReserved)) {
                return errorPages.render(site, siteDefault, HttpStatus.NOT_FOUND);
            }

            LocaleUrls.Prefix prefix = LocaleUrls.parse(path, site);
            // The URL implies the lookup locale: the prefix locale when present, else the site default.
            // Routing then matches only that locale's path, so a localized slug resolves under its own
            // locale only (and never doubles as the default URL). A (locale, path) collision throws —
            // caught below as a 500 — rather than silently shadowing one page.
            Locale lookupLocale = prefix.present() ? prefix.locale() : siteDefault;
            Optional<Page> found = routing.find(site, prefix.restPath(), lookupLocale);
            if (found.isPresent()) {
                // A REAL page always wins (the structural precedence).
                Page page = found.get();
                return dispatch(request, site, prefix, page, locale -> render(request, site, page, locale));
            }

            // The miss hook: a content source may serve this path (a blog entry, any plugin content). The SAME
            // dispatch ladder runs over it — virtual content behaves byte-identically to a page in every row
            // of the locale-dispatch table. A virtual-vs-virtual collision throws (caught below as a 500).
            if (contentRouter.hasVirtualSources()) {
                SiteView siteView = SiteViews.toView(site);
                Optional<ContentRouter.ContentMatch> match =
                        contentRouter.resolve(siteView, prefix.restPath(), lookupLocale);
                if (match.isPresent()) {
                    RoutableContent content = match.get().content();
                    return dispatch(request, site, prefix, content,
                            locale -> renderVirtual(request, site, siteView, match.get(), locale));
                }
            }
            // A page the operator marked as permanently removed answers 410, not 404 — the one status that
            // tells a search engine to drop the URL rather than keep retrying it, and tells a visitor the page
            // is not coming back. Checked before the near-miss redirect so an exact tombstone is never
            // second-guessed into a 301 at some similar-looking URL.
            if (routing.isGone(site, prefix.restPath(), lookupLocale)) {
                return errorPages.render(site, lookupLocale, HttpStatus.GONE);
            }
            // Before giving up: a trailing slash or a case difference is a near-miss, not a dead end. Redirect
            // to the canonical path rather than 404ing, so an inbound link with a stray slash still lands.
            // Costs one extra lookup ONLY on the miss path, never on a hit.
            Optional<String> canonical = routing.canonicalPathFor(site, prefix.restPath(), lookupLocale);
            if (canonical.isPresent()) {
                String target = prefix.present() ? "/" + prefix.locale().getLanguage() + canonical.get()
                        : canonical.get();
                return redirect(HttpStatus.MOVED_PERMANENTLY, withQuery(target, request, false), null);
            }
            return errorPages.render(site, lookupLocale, HttpStatus.NOT_FOUND);
        } catch (RuntimeException ex) {
            // A fail-closed collision or a broken page/master template — serve the site's _500 (or the
            // static fallback) rather than leaking a stack trace, and log loudly for the operator.
            log.error("serving '{}' for site '{}' failed", path, site.id(), ex);
            // A nonce already published belongs to a body that is NOT being sent: an error page has no ETag and
            // keeps the host-allowlist policy.
            PublicPageNonce.withdraw(request);
            return errorPages.render(site, siteDefault, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The locale-dispatch ladder (SITE-MANAGER-GUIDE §9), shared verbatim by real pages and virtual content —
     * one code object, so the two provably cannot drift: the {@code ?lang=} switcher 302 (+ preference
     * cookie), the default-prefix 301 to the bare canonical, the foreign-prefix 200/404, and the unprefixed
     * cookie → {@code Accept-Language} → default negotiation with the crawler guard ({@code Accept-Language}
     * is a preference only when the header is literally present — {@code request.getLocales()} fabricates the
     * JVM default otherwise, which would 302 a no-preference crawler/curl off the stable canonical URL).
     *
     * @param renderIn renders the target in the resolved locale (the page/virtual fork)
     */
    private ResponseEntity<String> dispatch(HttpServletRequest request, Site site, LocaleUrls.Prefix prefix,
                                            Routable target, Function<Locale, ResponseEntity<String>> renderIn) {
        Locale siteDefault = site.defaultLocale();

        // The language switcher: set the preference and redirect to the target's URL in the chosen locale.
        String langParam = request.getParameter("lang");
        if (langParam != null) {
            Locale chosen = LocaleNegotiator.supported(langParam, site);
            Locale target2 = (chosen != null && target.hasLocale(chosen, siteDefault)) ? chosen : siteDefault;
            // Drop `lang` on this hop only: it has just been consumed into the preference cookie, and
            // re-appending it would send the client straight back through the switcher.
            return redirect(HttpStatus.FOUND,
                    withQuery(LocaleUrls.urlFor(target, target2, siteDefault), request, true),
                    preferenceCookie(site.id(), target2.getLanguage()));
        }

        if (prefix.present()) {
            Locale loc = prefix.locale();
            if (loc.equals(siteDefault)) {
                // /en/about → 301 to the unprefixed canonical (the default locale's own path).
                return redirect(HttpStatus.MOVED_PERMANENTLY,
                        withQuery(LocaleUrls.urlFor(target, siteDefault, siteDefault), request, false), null);
            }
            if (!target.hasLocale(loc, siteDefault)) {
                return errorPages.render(site, siteDefault, HttpStatus.NOT_FOUND);
            }
            return renderIn.apply(loc);
        }

        List<Locale> accepted = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null
                ? List.of()
                : Collections.list(request.getLocales());
        Locale resolved = LocaleNegotiator.negotiate(site, target, cookieLocale(request, site.id()), accepted);
        if (resolved.equals(siteDefault)) {
            return renderIn.apply(resolved);
        }
        return redirect(HttpStatus.FOUND,
                withQuery(LocaleUrls.urlFor(target, resolved, siteDefault), request, false), null);
    }

    /**
     * Renders a content source's page: the tenant's own master chrome around the source's body template, with
     * the payload under {@code ${content.*}} (a clobber guard, not a security boundary), the master's widget
     * anchors filled, full head treatment, and the same conditional-request handling as a page — the validator
     * folds the content fingerprint + template/i18n/site inputs, so a {@code 304} never calls {@code body()}.
     * A throwing {@code body()} degrades to an empty payload (chrome still renders) rather than a 500.
     */
    private ResponseEntity<String> renderVirtual(HttpServletRequest request, Site site, SiteView siteView,
                                                 ContentRouter.ContentMatch match, Locale locale) {
        RoutableContent content = match.content();
        String cacheControl = content.cacheControl() != null ? content.cacheControl()
                : httpCache.publicPagesOrDefault();
        Map<String, String> query = queryParams(request);

        PageValidator validator = pageValidator.computeVirtual(site, content, locale, request.getQueryString());
        // The public-page CSP nonce derives from THIS ETag, so the 304 below and the 200 carry the same header.
        publishNonce(request, validator);
        boolean queryDependent = !query.isEmpty()
                && widgetModelBuilder.hasAnchors(site.id(), null, content.masterTemplate());
        // A substituted contribution while computing the validator (a head contributor's headInputs threw) means
        // the ETag does not describe what would be served: never answer 304 from it.
        if (!queryDependent && !ContributionGuard.isDegraded(request) && validator.isNotModified(request)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(validator.etag())
                    .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                    .build();
        }

        String baseUrl = siteView.baseUrl() == null ? "" : siteView.baseUrl();
        String masterName = SiteTemplateResolver.PREFIX + site.id() + "/" + content.masterTemplate();
        Map<String, Object> model =
                modelBuilder.build(siteView, content, locale, masterName, request.getRequestURI(), baseUrl);

        // The body payload — fetched ONLY here, on an actual 200 render (never for the 304 above).
        // Contained: a body() that throws (or returns null) renders an empty body inside the chrome, served UNCACHEABLY
        // below — a transient fault must never be pinned by caches.
        Optional<ContentBody> fetched = contentRouter.body(match, siteView, locale);
        ContentBody body = fetched.orElse(ContentBody.empty());
        Map<String, Object> contentModel = new HashMap<>();
        contentModel.put("id", content.ref().id());
        contentModel.put("sourceKey", content.sourceKey());
        contentModel.put("title", content.titleFor(locale));
        contentModel.put("description", content.descriptionFor(locale));
        contentModel.put("bodyHtml", body.bodyHtml());
        contentModel.put("attributes", body.attributes());
        contentModel.put("updatedAt", content.ref().updatedAt() == null ? "" : content.ref().updatedAt().toString());
        model.put("content", Map.copyOf(contentModel));

        // Only the MASTER's widget anchors: the body is a plugin-owned template, not a tenant file to scan.
        model.put("widgets", widgetModelBuilder.buildForMaster(site.id(), content.masterTemplate(), siteView,
                (PageMeta) model.get("page"), locale, query, request.getRequestURI()));
        Map<String, String> urls = new HashMap<>(routing.urlMap(site, locale));
        urls.putAll(contentCatalog.urlMap(siteView, locale));
        model.put("urls", urls);
        request.setAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE,
                modelBuilder.headContext(siteView, content, locale, baseUrl, false,
                        RequestOrigin.trusted(baseUrl, request)));

        String html = renderer.render(site.id(), content.ref().bodyTemplate(), locale, model);
        if (fetched.isEmpty() || ContributionGuard.isDegraded(request)) {
            // The empty-body fallback must NOT be served as a healthy cacheable representation: the validator
            // folds only metadata/templates, so the healthy and degraded outputs would share one strong ETag
            // and every revalidation would 304-pin the broken page in clients/CDNs until an unrelated input
            // changed. No validators, no-store — the next request re-renders. No ETag ⇒ no derived nonce either.
            PublicPageNonce.withdraw(request);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html);
        }
        return validated(html, validator, cacheControl, queryDependent);
    }

    private ResponseEntity<String> render(HttpServletRequest request, Site site, Page page, Locale locale) {
        // The effective Cache-Control for this page: its own override if set, else the configured platform
        // default. It coexists with the ETag below — max-age bounds how long a CDN/browser may
        // serve without asking; the validator still busts the cache the instant the page's content changes.
        String cacheControl = page.cacheControl() != null ? page.cacheControl() : httpCache.publicPagesOrDefault();

        // The request's query params, handed to each content widget so it can render request-dependent output
        // (a search-results widget reading ?q=, a form widget reading a ?sent confirmation) on a normal page.
        Map<String, String> query = queryParams(request);

        // Conditional GET: the validator folds every input that shapes the output — INCLUDING the query string,
        // since a query-aware widget's output depends on it — so an unchanged page returns 304 without rendering,
        // and ?q=/?sent variants get distinct ETags rather than colliding with the no-query render.
        PageValidator validator = pageValidator.compute(site, page, locale, request.getQueryString());
        // The public-page CSP nonce derives from THIS ETag, so the 304 below and the 200 carry the same header.
        publishNonce(request, validator);
        // A query that reaches a widget may be answered from data no validator can see (a search index): such a
        // render is never revalidated — see validated().
        boolean queryDependent = !query.isEmpty()
                && widgetModelBuilder.hasAnchors(site.id(), page.slug(), page.template());
        // Never a 304 from a validator computed while a contribution was being substituted.
        if (!queryDependent && !ContributionGuard.isDegraded(request) && validator.isNotModified(request)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(validator.etag())
                    .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                    .build();
        }

        SiteView siteView = SiteViews.toView(site);
        String baseUrl = siteView.baseUrl() == null ? "" : siteView.baseUrl();
        String masterName = SiteTemplateResolver.PREFIX + site.id() + "/" + page.template();
        Map<String, Object> model =
                modelBuilder.build(siteView, page, locale, masterName, request.getRequestURI(), baseUrl);
        // Pre-render the page's and master's content widgets into the model; the kernel-generated
        // decoupled-logic rules (WidgetAwareDecoupledLogicResolver) fill each data-widget anchor from
        // this map. Empty/unknown widgets land as "", clearing the author's preview placeholder.
        model.put("widgets", widgetModelBuilder.build(site.id(), page.slug(), page.template(),
                siteView, (PageMeta) model.get("page"), locale, query, request.getRequestURI()));
        // By-id internal links: ${urls['<slug>']} (pages) and ${urls['<sourceKey>:<id>']} (source content)
        // resolve to locale-correct URLs (the target's URL in this locale, else its primary), so links follow
        // localized slugs and never 404. ':' cannot appear in a page slug, so the key spaces cannot collide.
        Map<String, String> urls = new HashMap<>(routing.urlMap(site, locale));
        urls.putAll(contentCatalog.urlMap(siteView, locale));
        model.put("urls", urls);
        // The edge head-injection filter splices HeadContributor markup before </head>. It reads this
        // host-materialized context after the request's site binding has already been cleared, so it
        // must be stashed here, while the site is still bound. (preview=false until draft preview lands.)
        request.setAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE,
                modelBuilder.headContext(siteView, page, locale, baseUrl, false,
                        RequestOrigin.trusted(baseUrl, request)));
        String html = renderer.render(site.id(), SiteTemplateResolver.PREFIX + site.id() + "/" + page.slug(), locale, model);
        if (ContributionGuard.isDegraded(request)) {
            // A widget (or a head contributor's headInputs) was substituted: this body is not the representation
            // the validator describes. No validators, no-store, and no ETag-derived nonce — the next request
            // re-renders, and no cache can pin the hole.
            PublicPageNonce.withdraw(request);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html);
        }
        return validated(html, validator, cacheControl, queryDependent);
    }

    /**
     * The healthy {@code 200} of a page or virtual render.
     *
     * <p>Normally it carries the strong ETag and the page's {@code Cache-Control}, and <b>no {@code Last-Modified}</b>:
     * the ETag folds inputs that have no modification time (a console save, a reload, a content source), so a date
     * could not move with it and would answer {@code 304} to an {@code If-Modified-Since}-only client across a change.
     *
     * <p>A <b>query-dependent</b> render — a request query on a page whose page or master anchors a content widget —
     * is served with no validator and {@value #QUERY_RENDER_CACHE_CONTROL}, whatever the page's own
     * {@code cacheControl}. A widget may answer the query from data the kernel cannot fold (the search plugin reads
     * its index; SPI 1.0.0 gives a widget no way to declare a data version), so no ETag could describe the body: a
     * shared or revalidated copy would pin stale results for as long as the page's own files stayed put. The query
     * still folds into the ETag the nonce is derived from, so the CSP header and the body stamp agree.
     */
    private static ResponseEntity<String> validated(String html, PageValidator validator, String cacheControl,
                                                    boolean queryDependent) {
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
        if (queryDependent) {
            return ok.header(HttpHeaders.CACHE_CONTROL, QUERY_RENDER_CACHE_CONTROL).body(html);
        }
        return ok.eTag(validator.etag()).header(HttpHeaders.CACHE_CONTROL, cacheControl).body(html);
    }

    /** Publishes the ETag-derived public-page CSP nonce for this response (a no-op when the feature is off). */
    private void publishNonce(HttpServletRequest request, PageValidator validator) {
        if (publicNonce != null) {
            publicNonce.publish(request, validator.etag());
        }
    }

    /** The request's query params as first-value-per-name — the read-only view handed to each content widget. */
    private static Map<String, String> queryParams(HttpServletRequest request) {
        Map<String, String> q = new java.util.HashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0 && v[0] != null) {
                q.put(k, v[0]);
            }
        });
        return q;
    }


    /**
     * Appends the request's query string to a redirect {@code Location}.
     *
     * <p>Without this a redirect silently discards whatever the visitor typed: {@code /en/search?q=espresso}
     * became {@code /search}, losing the search term on the hop. Locale redirects are the common path on a
     * multilingual site, so this affected real visitors rather than only crawler tidiness.
     *
     * <p><b>CR/LF are stripped even though the container should never deliver them.</b> The value goes into a
     * response header, so a newline surviving request-line parsing would let a client forge headers. Defence in
     * depth is one call here, and the cost of being wrong is response splitting.
     *
     * @param dropLang drop a {@code lang} parameter — true only on the language-switcher hop, where it has
     *                 already been consumed and re-appending it would bounce the client back through the switch
     */
    private static String withQuery(String location, HttpServletRequest request, boolean dropLang) {
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return location;
        }
        query = query.replaceAll("[\\r\\n]", "");
        if (dropLang) {
            query = java.util.Arrays.stream(query.split("&"))
                    .filter(part -> !part.equals("lang") && !part.startsWith("lang="))
                    .collect(java.util.stream.Collectors.joining("&"));
        }
        if (query.isBlank()) {
            return location;
        }
        return location + (location.indexOf('?') >= 0 ? "&" : "?") + query;
    }

    private static ResponseEntity<String> redirect(HttpStatus status, String location, ResponseCookie cookie) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).header(HttpHeaders.LOCATION, location);
        if (cookie != null) {
            builder = builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }

    private static ResponseCookie preferenceCookie(String siteId, String languageCode) {
        return ResponseCookie.from(LocaleNegotiator.preferenceCookieName(siteId), languageCode)
                .path("/").httpOnly(true).sameSite("Lax").build();
    }

    private static String cookieLocale(HttpServletRequest request, String siteId) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = LocaleNegotiator.preferenceCookieName(siteId);
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static boolean isReserved(String path) {
        return ReservedPaths.atRequestTime(path); // the single kernel authority (same package)
    }
}
