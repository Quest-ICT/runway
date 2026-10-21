package mx.quest.runway.page;

import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.icon.SiteIcon;
import mx.quest.runway.icon.SiteIconService;
import mx.quest.runway.icon.SiteManifest;
import mx.quest.runway.icon.SiteManifestService;
import java.util.LinkedHashMap;
import mx.quest.runway.seo.SeoConfigService;
import mx.quest.runway.site.SiteContact;
import mx.quest.runway.site.SiteContactService;
import mx.quest.runway.site.SiteParamsService;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.IconHints;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SeoTags;
import mx.quest.runway.spi.model.SiteView;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Assembles the immutable render model (the MODEL step) — the variables the page's and master's
 * {@code .th.xml} bind against. The host resolves everything here, so the templates are a pure
 * function of these values.
 *
 * <p>Includes the multilingual model: {@code alternates}/{@code xDefault} (from the page's available
 * locales, for {@code hreflang}) and {@code siteLocales} (the language switcher's options).
 */
@Component
public class RenderModelBuilder {

    /** Per-site head inputs, materialized into the {@link HeadContext}; {@code null} in bare unit tests. */
    private final SeoConfigService seoConfig;
    private final SiteIconService iconService;
    private final SiteManifestService manifestService;
    /** Per-site template parameters overlaid by the page's own; {@code null} in bare unit tests ⇒ no site params. */
    private final SiteParamsService siteParams;
    /** The site's contact information ({@code ${contact}}); {@code null} in bare unit tests ⇒ an empty contact. */
    private final SiteContactService contact;

    /** No-arg form for unit tests that build a model without Spring — head/param inputs resolve to their empties. */
    public RenderModelBuilder() {
        this(null, null, null, null);
    }

    /** SEO-only form (a render test that exercises the SEO path without the icon/param services). */
    public RenderModelBuilder(SeoConfigService seoConfig) {
        this(seoConfig, null, null, null);
    }

    /** Params-only form (a render test that exercises the site-params merge without the head services). */
    public RenderModelBuilder(SiteParamsService siteParams) {
        this(null, null, null, siteParams);
    }

    /** The form without contact information (bare tests; {@code ${contact}} is then the empty contact). */
    public RenderModelBuilder(SeoConfigService seoConfig, SiteIconService iconService,
                              SiteManifestService manifestService, SiteParamsService siteParams) {
        this(seoConfig, iconService, manifestService, siteParams, null);
    }

    @Autowired
    public RenderModelBuilder(SeoConfigService seoConfig, SiteIconService iconService,
                              SiteManifestService manifestService, SiteParamsService siteParams,
                              SiteContactService contact) {
        this.seoConfig = seoConfig;
        this.iconService = iconService;
        this.manifestService = manifestService;
        this.siteParams = siteParams;
        this.contact = contact;
    }

    /**
     * Builds the model for rendering {@code page} in {@code locale}.
     *
     * @param site               the resolved site view
     * @param page               the loaded page or virtual content (the {@link Routable} dispatch view)
     * @param locale             the resolved locale
     * @param masterTemplateName the site-scoped name of the page's master (built by the caller)
     * @param requestPath        the current request path (for the switcher's {@code ?lang=} links)
     * @param baseUrl            the site's base URL (prepended to canonical/alternate URLs; may be empty)
     */
    public Map<String, Object> build(SiteView site, Routable page, Locale locale, String masterTemplateName,
                                     String requestPath, String baseUrl) {
        Locale siteDefault = site.defaultLocale();

        Map<String, Object> model = new HashMap<>();
        model.put("site", site);
        model.put("page", page.meta(locale, siteDefault));
        model.put("locale", locale);
        model.put("master", masterTemplateName);
        model.put("pageTitle", page.titleFor(locale));
        model.put("pageDescription", page.descriptionFor(locale));
        model.put("canonical", ContentUrls.canonicalUrl(page, locale, siteDefault, baseUrl));
        model.put("noindex", page.noindex());
        model.put("activeNav", page.activeNav());
        // A STRING, not the int — a template that feeds it to a #{key(${year})} message would otherwise have
        // MessageFormat number-format it with the locale's grouping separator ("2,026"/"2.026"/"2 026") instead
        // of a plain "2026". As a string it is inserted verbatim (and th:text="${year}" still renders "2026").
        model.put("year", String.valueOf(Year.now().getValue()));

        // Template parameters: the site-wide params overlaid by this page's own (the page wins on a key
        // collision). Always present (possibly empty), so a template's ${params.key} never NPEs.
        model.put("params", mergedParams(site, page));

        // The contact details a site manager enters on Site config (email, phone, address): ${contact.email} and
        // so on, and ${contact.isEmpty()} to hide an empty block. Always present. Until 1.0.0 they were stored
        // and read by nothing. PageValidatorService folds them into the ETag (LoadedSiteInputs.siteContact).
        model.put("contact", contact == null ? SiteContact.EMPTY : contact.get(site.id()));

        // hreflang alternates from the page's available locales (never the site's full set).
        model.put("alternates", ContentUrls.alternates(page, site, baseUrl));
        model.put("xDefault", ContentUrls.xDefault(page, site, baseUrl));

        // Language switcher: all the site's locales, each a ?lang link on the current page.
        model.put("siteLocales", site.locales().stream()
                .map(l -> new LocaleOption(l.getLanguage(), l.getDisplayLanguage(l),
                        switchUrl(requestPath, l.getLanguage()), l.equals(locale)))
                .toList());
        return model;
    }

    /**
     * Builds the immutable {@link HeadContext} the edge head-injection filter passes to each
     * {@link mx.quest.runway.spi.HeadContributor}. It reuses the same canonical / alternate / x-default
     * URLs {@link #build} puts in the render model, so the {@code <head>} markup and the rendered page
     * never disagree about a page's URLs.
     *
     * @param site    the resolved site view
     * @param page    the loaded page or virtual content (the {@link Routable} dispatch view)
     * @param locale  the resolved locale
     * @param baseUrl the site's base URL (prepended to canonical/alternate URLs; may be empty)
     * @param preview whether this is a draft/preview render; when {@code true} the effective
     *                {@code noindex} is forced on (a preview is never indexable)
     * @return the head context for this request (never {@code null})
     */
    public HeadContext headContext(SiteView site, Routable page, Locale locale, String baseUrl, boolean preview) {
        return headContext(site, page, locale, baseUrl, preview, "");
    }

    /**
     * As {@link #headContext(SiteView, Routable, Locale, String, boolean)}, carrying the page's <b>trusted origin</b>
     * ({@link HeadContext#origin()}, SPI 1.0.0) — what a contributor builds absolute URLs with when the site has no
     * configured base URL (JSON-LD must never be relative). The canonical/alternate/x-default URLs keep their own rule
     * (prefixed with {@code baseUrl} only), so the head's links are unchanged.
     *
     * @param origin {@code scheme://host[:port]}: the configured base URL when set, else the request's origin after
     *               the trusted-proxy gate ({@code web.RequestOrigin#trusted}); {@code ""} when unknown
     */
    public HeadContext headContext(SiteView site, Routable page, Locale locale, String baseUrl, boolean preview,
                                   String origin) {
        Locale siteDefault = site.defaultLocale();
        PageMeta meta = page.meta(locale, siteDefault);
        boolean noindex = preview || meta.noindex();
        SeoTags seo = seoConfig == null ? SeoTags.empty() : seoConfig.get(site.id()).toTags();
        return new HeadContext(site, meta, locale,
                ContentUrls.canonicalUrl(page, locale, siteDefault, baseUrl),
                ContentUrls.xDefault(page, site, baseUrl),
                ContentUrls.alternates(page, site, baseUrl), preview, noindex, seo, iconHints(site.id()), origin);
    }

    /**
     * The effective template parameters for the model: the site's params (empty when the params service is
     * absent, as in bare unit tests) overlaid by the page's own, so the page wins on a key collision. The
     * result is an immutable copy the template reads as {@code ${params.key}}; insertion order (site keys
     * first, then page-only keys) is preserved for a stable iteration should a template list them.
     */
    private Map<String, String> mergedParams(SiteView site, Routable page) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (siteParams != null) {
            merged.putAll(siteParams.get(site.id()));
        }
        merged.putAll(page.params()); // page overlays site
        return Map.copyOf(merged);
    }

    /** Resolves the site's icon/manifest discovery hints for the head contributor (empty in bare tests). */
    private IconHints iconHints(String siteId) {
        if (iconService == null) {
            return IconHints.NONE;
        }
        Set<SiteIcon> present = iconService.presentSlots(siteId);
        SiteManifest manifest = manifestService == null ? SiteManifest.EMPTY : manifestService.get(siteId);
        boolean hasManifest = manifest.hasAny() || present.stream().anyMatch(RenderModelBuilder::isManifestIcon);
        List<String> keys = present.stream().map(SiteIcon::key).toList();
        return new IconHints(keys, hasManifest, manifest.themeColor());
    }

    private static boolean isManifestIcon(SiteIcon icon) {
        return icon == SiteIcon.ICON_192 || icon == SiteIcon.ICON_512 || icon == SiteIcon.SVG_FAVICON;
    }

    private static String switchUrl(String requestPath, String code) {
        String base = (requestPath == null || requestPath.isEmpty()) ? "/" : requestPath;
        return base + (base.indexOf('?') >= 0 ? "&" : "?") + "lang=" + code;
    }
}
