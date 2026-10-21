package mx.quest.discover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * SEO+ and GEO — the single {@code HeadContributor} that injects one schema.org {@code @graph} of JSON-LD into
 * every indexable page's {@code <head>}. This is the layer the lean kernel deliberately leaves out: the kernel
 * already emits canonical, hreflang, robots, Open Graph and Twitter cards, so this contributor adds only the
 * structured-data {@code @graph} — never a tag the kernel already produces.
 *
 * <p>It emits <b>one</b> {@code <script type="application/ld+json">} block whose {@code @graph} carries each
 * entity <b>exactly once</b>: {@code WebSite} (with the GEO {@code SearchAction} folded in as its
 * {@code potentialAction}), {@code Organization}, {@code WebPage}/{@code Article} (with the GEO
 * {@code speakable} folded in), and {@code BreadcrumbList} on deep pages. Folding GEO into the same nodes — rather
 * than re-declaring {@code #website}/{@code #webpage} in a second block — means a JSON-LD consumer that parses
 * each {@code <script>} independently still sees complete entities.
 *
 * <p>Pure and stateless. Emits nothing for a preview or {@code noindex} render (structured data belongs only on
 * indexable pages), or when the tenant turns {@code jsonld.enabled} off (which also suppresses the GEO signals,
 * since a {@code SearchAction}/{@code speakable} that references an undefined {@code #website}/{@code #webpage}
 * would be an orphan). GEO signals are additionally gated by the opt-in {@code geo.enabled} flag. All URLs are
 * anchored on the host-resolved {@link HeadContext#canonical()} so the graph is internally consistent — made
 * absolute against {@link HeadContext#origin()} on a host-less site, whose canonical is root-relative (since 2.1.0;
 * it is why the plugin needs SPI 1.0.0).
 */
public class StructuredDataHead implements HeadContributor {

    static final String PLUGIN_ID = "discoverability";

    /**
     * Default {@code speakable} selectors when the tenant has not customized {@code geo.speakable}. Only elements
     * the shipped seed master (and a typical custom master) actually render — {@code <article>} is intentionally
     * NOT a default, since the seed masters have none and a phantom selector helps no assistant. A tenant whose
     * master really has {@code <article>} adds it back in the per-site GEO settings.
     */
    private static final List<String> DEFAULT_SPEAKABLE = List.of("h1", "main");

    private final PluginSettings settings;
    private final Map<String, SchemaType> types;

    public StructuredDataHead(PluginSettings settings, Map<String, SchemaType> types) {
        this.settings = settings;
        this.types = types == null ? Map.of() : types;
    }

    @Override
    public String key() {
        return "discoverability/structured-data";
    }

    @Override
    public String render(HeadContext ctx) {
        if (ctx.preview() || ctx.noindex()) {
            return "";
        }
        Map<String, Object> cfg = settings.site(ctx.site().id()).values();
        if (!SiteConfig.bool(cfg, "jsonld.enabled", true)) {
            return "";
        }
        if (ctx.canonical() == null || ctx.canonical().isBlank()) {
            return ""; // no URL to anchor the graph — emit nothing rather than a broken graph
        }
        String base = baseFor(ctx, ctx.canonical());
        // A host-less site's canonical is root-relative (the kernel prefixes only a base URL it knows); JSON-LD
        // URLs and @ids must be absolute, so it is anchored on the trusted origin the page is served under.
        String canonical = absolute(ctx.canonical(), base);
        String siteUrl = base.isBlank() ? null : base + "/";
        String path = pathOf(base, canonical);
        boolean geo = SiteConfig.bool(cfg, "geo.enabled", false);
        List<Map<String, Object>> graph = new ArrayList<>();

        // WebSite — the GEO SearchAction is folded in as potentialAction so #website is defined exactly once.
        Map<String, Object> website = Ld.node("WebSite", Ld.websiteId(base));
        Ld.put(website, "url", siteUrl);
        Ld.put(website, "name", ctx.site().displayName());
        Ld.put(website, "inLanguage", ctx.locale().toLanguageTag());
        if (geo && !base.isBlank()) {
            String searchUrl = SiteConfig.str(cfg, "search.url", "");
            if (!searchUrl.isBlank()) {
                website.put("potentialAction", searchAction(base, searchUrl));
            }
        }
        graph.add(website);

        // Organization (only when the tenant has named one)
        String orgName = SiteConfig.str(cfg, "org.name", "");
        boolean hasOrg = !orgName.isBlank();
        if (hasOrg) {
            Map<String, Object> org = Ld.node("Organization", Ld.organizationId(base));
            Ld.put(org, "name", orgName);
            Ld.put(org, "url", siteUrl);
            Ld.put(org, "logo", absolutize(SiteConfig.str(cfg, "org.logo", ""), base));
            Ld.putList(org, "sameAs", SiteConfig.list(cfg, "org.sameas"));
            graph.add(org);
        }

        // The page's own node — its type is the site's Default page type, or the page type its content.schema entry
        // declares (PageTypes: the same answer the page editor shows). The GEO speakable is folded in so #webpage is
        // defined exactly once.
        PageTypes.Description described = PageTypes.describe(cfg, path, types);
        String type = described.type();
        Map<String, Object> page = Ld.node(type, Ld.webpageId(canonical));
        Ld.put(page, "url", canonical);
        Ld.put(page, "Article".equals(type) ? "headline" : "name", ctx.page().title());
        Ld.put(page, "description", ctx.page().description());
        Ld.put(page, "inLanguage", ctx.locale().toLanguageTag());
        Ld.ref(page, "isPartOf", Ld.websiteId(base));
        if (hasOrg) {
            Ld.ref(page, "publisher", Ld.organizationId(base));
        }
        if (geo) {
            List<String> selectors = SiteConfig.list(cfg, "geo.speakable");
            if (selectors.isEmpty()) {
                selectors = DEFAULT_SPEAKABLE;
            }
            Map<String, Object> speakable = new LinkedHashMap<>();
            speakable.put("@type", "SpeakableSpecification");
            speakable.put("cssSelector", selectors);
            page.put("speakable", speakable);
        }
        graph.add(page);
        if (described.pageBlock() != null) {
            types.get(type).describePage(page, ctx,
                    schemaContext(ctx, base, canonical, path, hasOrg, described.pageBlock()), graph);
        }

        // SoftwareApplication — a product/app entity emitted on the HOMEPAGE ONLY (so it never leaks onto
        // interior pages the way a hand-authored extra-head block did), @id-linked into this same graph. The
        // tenant configures it via software.* instead of hand-authoring raw JSON-LD in seo.yml's extraHead.
        if (SiteConfig.bool(cfg, "software.enabled", false) && isHome(path, ctx)) {
            graph.add(softwareApplication(cfg, base, siteUrl, canonical, orgName, hasOrg, ctx));
        }

        // Type-aware structured data — a tenant declares a page/section type in content.schema; the matching
        // SchemaType maps it to schema.org nodes that reference this WebPage / Organization by @id. The head
        // contributor stays type-blind: it dispatches through the registry, never branches on a concrete type.
        appendTypedNodes(ctx, described.added(), base, canonical, path, hasOrg, graph);

        // BreadcrumbList (home → [collection] → this page), @id'd and linked from WebPage.breadcrumb.
        if (SiteConfig.bool(cfg, "jsonld.breadcrumbs", true) && siteUrl != null) {
            Map<String, Object> crumbs =
                    breadcrumbs(base, canonical, path, ctx.page().title(), collectionParent(cfg, path, ctx));
            if (crumbs != null) {
                graph.add(crumbs);
                Ld.ref(page, "breadcrumb", (String) crumbs.get("@id"));
            }
        }

        return Ld.script(graph);
    }

    /**
     * Builds the site's {@code SoftwareApplication} node from the {@code software.*} config. {@code name}
     * falls back to the organization name / site display name; {@code url} to the homepage; {@code offers} is
     * emitted only when a price is set (price {@code 0} = free); {@code publisher} references the Organization.
     */
    private static Map<String, Object> softwareApplication(Map<String, Object> cfg, String base, String siteUrl,
            String canonical, String orgName, boolean hasOrg, HeadContext ctx) {
        Map<String, Object> app = Ld.node("SoftwareApplication", Ld.softwareId(base));
        String name = SiteConfig.str(cfg, "software.name", "");
        if (name.isBlank()) {
            name = orgName.isBlank() ? ctx.site().displayName() : orgName;
        }
        Ld.put(app, "name", name);
        String url = SiteConfig.str(cfg, "software.url", "");
        Ld.put(app, "url", !url.isBlank() ? url : (siteUrl != null ? siteUrl : canonical));
        Ld.put(app, "applicationCategory", SiteConfig.str(cfg, "software.category", ""));
        Ld.put(app, "operatingSystem", SiteConfig.str(cfg, "software.os", "Web"));
        String description = SiteConfig.str(cfg, "software.description", "");
        Ld.put(app, "description", description.isBlank() ? ctx.page().description() : description);
        Ld.put(app, "inLanguage", ctx.locale().toLanguageTag());
        Ld.put(app, "license", SiteConfig.str(cfg, "software.license", ""));
        Ld.put(app, "screenshot", SiteConfig.str(cfg, "software.screenshot", ""));
        String price = SiteConfig.str(cfg, "software.price", "");
        if (!price.isBlank()) {
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("@type", "Offer");
            offer.put("price", price);
            offer.put("priceCurrency", SiteConfig.str(cfg, "software.currency", "USD"));
            app.put("offers", offer);
        }
        if (hasOrg) {
            Ld.ref(app, "publisher", Ld.organizationId(base));
        }
        return app;
    }

    /**
     * Appends the nodes of the blocks that add an entity to the page (a product, a service, a business, a person, a
     * list), each through its registered {@link SchemaType}. The block that describes the page itself has already
     * shaped the page's own node; {@link PageTypes} decided which is which.
     */
    private void appendTypedNodes(HeadContext ctx, List<Map<String, Object>> blocks, String base, String canonical,
            String path, boolean hasOrg, List<Map<String, Object>> graph) {
        // A path may declare SEVERAL type-blocks (e.g. an FAQPage AND a set of Offers on a pricing page);
        // dispatch each through the registry. The contributor stays type-blind — no instanceof/switch.
        for (Map<String, Object> block : blocks) {
            SchemaType type = types.get(Schemas.str(block, "type", ""));
            List<Map<String, Object>> nodes =
                    type.nodes(ctx, schemaContext(ctx, base, canonical, path, hasOrg, block));
            if (nodes != null) {
                for (Map<String, Object> node : nodes) {
                    if (node != null && !node.isEmpty()) {
                        graph.add(node);
                    }
                }
            }
        }
    }

    private static SchemaContext schemaContext(HeadContext ctx, String base, String canonical, String path,
            boolean hasOrg, Map<String, Object> block) {
        return new SchemaContext(base, canonical, path, isHome(path, ctx), hasOrg,
                Ld.websiteId(base), Ld.webpageId(canonical), hasOrg ? Ld.organizationId(base) : null,
                block, ctx.locale());
    }

    /**
     * Resolves a root-relative URL (one that starts with {@code /}) to absolute against the site base, so a
     * portable seed can ship {@code org.logo: /static/img/logo.svg} without hard-coding a host; an
     * already-absolute or blank value passes through unchanged.
     */
    private static String absolutize(String url, String base) {
        return (url != null && url.startsWith("/") && !base.isBlank()) ? base + url : url;
    }

    /**
     * The path portion of the (absolute) canonical relative to the site base ({@code "/"} for the home). Guards
     * against a per-page <b>canonical override to a different host</b>: when the canonical does not start with
     * the site base (or its own origin), the raw canonical is returned — which is neither {@code "/"} nor a
     * {@code /}-rooted path, so {@code isHome()}, {@code content.schema} matching and breadcrumbs all correctly
     * treat it as "not this site's home / no path match" rather than slicing a garbage substring. Package-private
     * so the FAQ advisory can resolve a page's path the same way the render-time typing does.
     */
    static String pathOf(String base, String canonical) {
        String origin = (base == null || base.isBlank()) ? Ld.origin(canonical) : base;
        if (!origin.isBlank() && canonical.startsWith(origin)) {
            String path = canonical.substring(origin.length());
            return path.isEmpty() ? "/" : path;
        }
        return canonical; // canonical diverges from the site base (a cross-domain per-page canonical override)
    }

    /**
     * Whether {@code path} addresses a homepage — the default-locale home ({@code "/"}) or a localized home
     * ({@code /<locale>}), matched against the site's actual locale set so a real 2-letter slug is not mistaken
     * for a locale prefix.
     */
    private static boolean isHome(String path, HeadContext ctx) {
        String p = (path.length() > 1 && path.endsWith("/")) ? path.substring(0, path.length() - 1) : path;
        if (p.isEmpty() || p.equals("/")) {
            return true;
        }
        String seg = p.startsWith("/") ? p.substring(1) : p;
        Locale def = ctx.site().defaultLocale();
        for (Locale locale : ctx.site().locales()) {
            // The DEFAULT locale's home is "/" (unprefixed), never "/<code>", so a "/<default-code>" path is a
            // real page, not a home — skipping it removes the most likely code-vs-slug collision (e.g. "/en").
            if (locale.equals(def)) {
                continue;
            }
            if (seg.equalsIgnoreCase(locale.getLanguage()) || seg.equalsIgnoreCase(locale.toLanguageTag())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The site base: the site's base URL (configured, or derived from its host name), else — a host-less site,
     * such as the platform's catch-all default — the trusted origin the page is served under
     * ({@link HeadContext#origin()}, SPI 1.0.0: the request's scheme and host as the kernel's trusted-proxy gate left
     * them, never a raw {@code X-Forwarded-*} header), else the origin of an absolute canonical. {@code ""} only
     * when none is known. Until 2.1.0 a host-less site had no base here, so its graph was root-relative and carried
     * no {@code SearchAction} and no breadcrumb trail.
     */
    static String baseFor(HeadContext ctx, String canonical) {
        String configured = ctx.site().baseUrl();
        String base;
        if (configured != null && !configured.isBlank()) {
            base = configured.trim();
        } else if (!ctx.origin().isBlank()) {
            base = ctx.origin().trim();
        } else {
            base = Ld.origin(canonical);
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * {@code canonical} made absolute against {@code base} when it is root-relative ({@code /about}); an absolute
     * canonical (the usual case, and every cross-domain override) passes through unchanged, and with no base known
     * ({@code ""}) a root-relative one stays as it is. A protocol-relative {@code //host/x} is left alone — it already
     * names a host.
     */
    static String absolute(String canonical, String base) {
        return canonical.startsWith("/") && !canonical.startsWith("//") ? base + canonical : canonical;
    }

    /**
     * Builds a schema.org {@code SearchAction} for the site's search URL. A {@code {query}} or
     * {@code {search_term_string}} placeholder is honored; otherwise a {@code q=} parameter is appended.
     */
    private static Map<String, Object> searchAction(String base, String searchUrl) {
        String template = searchUrl.startsWith("http")
                ? searchUrl
                : base + (searchUrl.startsWith("/") ? "" : "/") + searchUrl;
        if (template.contains("{query}")) {
            template = template.replace("{query}", "{search_term_string}");
        } else if (!template.contains("{search_term_string}")) {
            template = template + (template.contains("?") ? "&" : "?") + "q={search_term_string}";
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("@type", "SearchAction");
        // NEVER Map.of() for a node that is serialized: java.util.Map.of randomizes iteration order PER JVM
        // INSTANCE (ImmutableCollections.SALT), so the emitted JSON-LD key order flipped on roughly every
        // other restart — stable within one run, different after the next. Found in a live deployment by an operator
        // byte-diffing homepages across an upgrade, who reasonably read it as a one-off change between builds;
        // it was not, it was every restart, forever. Harmless semantically (the graphs are equal), but it made
        // rendered output non-reproducible and quietly broke the promise of the page's strong ETag, which
        // folds metadata and templates and therefore did NOT change when the body did.
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("@type", "EntryPoint");
        target.put("urlTemplate", template);
        action.put("target", target);
        action.put("query-input", "required name=search_term_string");
        return action;
    }

    /**
     * A {@code Home → [collection] → page} breadcrumb with a stable {@code @id} (so the {@code WebPage} can point
     * at it); {@code null} for the home page (or a cross-domain canonical). When {@code parent} is non-null (a
     * content.schema {@code breadcrumb: {name, url}} on a detail page under a collection) it becomes the middle
     * level, so a product's trail reads {@code Home → Coffees → Ethiopia Guji} rather than {@code Home → Guji}.
     */
    private static Map<String, Object> breadcrumbs(String base, String canonical, String path, String pageTitle,
            Map<String, Object> parent) {
        if (path.isBlank() || path.equals("/") || !path.startsWith("/")) {
            return null; // home, or a canonical that isn't a path under this site — no breadcrumb trail
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 1;
        items.add(listItem(position++, "Home", base + "/"));
        if (parent != null) {
            String parentUrl = Schemas.str(parent, "url");
            items.add(listItem(position++, Schemas.str(parent, "name"),
                    parentUrl != null && parentUrl.startsWith("/") ? base + parentUrl : parentUrl));
        }
        items.add(listItem(position, (pageTitle == null || pageTitle.isBlank()) ? path : pageTitle, canonical));
        Map<String, Object> list = Ld.node("BreadcrumbList", canonical + "#breadcrumb");
        list.put("itemListElement", items);
        return list;
    }

    private static Map<String, Object> listItem(int position, String name, String item) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@type", "ListItem");
        node.put("position", position);
        Ld.put(node, "name", name);
        Ld.put(node, "item", item);
        return node;
    }

    /** The optional parent-collection level a page declares in content.schema ({@code breadcrumb: {name, url}}). */
    private static Map<String, Object> collectionParent(Map<String, Object> cfg, String path, HeadContext ctx) {
        Object entry = PageTypes.entry(cfg, path);
        for (Map<String, Object> block : PageSchemaConfig.blocks(entry)) {
            if (block.get("breadcrumb") instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parent = (Map<String, Object>) map;
                if (Schemas.str(parent, "name") != null && Schemas.str(parent, "url") != null) {
                    return parent;
                }
            }
        }
        return null;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("head.structured-data", "head.per-locale", "head.geo");
    }

    /**
     * This contributor's whole per-site input: its own settings file.
     *
     * <p>Declared so the kernel can fold it into each page's {@code ETag}. Head markup is spliced in at the
     * response edge, <b>after</b> the page validator has been computed and may already have answered
     * {@code 304} — so without this, changing a per-site setting here (organisation name, GEO, the search URL)
     * changed the delivered page and not its validator, and every client and CDN holding the old one kept the
     * previous markup indefinitely.
     */
    @Override
    public java.util.List<String> headInputs(String siteId) {
        return java.util.List.of("plugins/discoverability.yml");
    }
}
