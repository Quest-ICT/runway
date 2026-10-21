package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.Provenance;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/** SEO+ structured-data head: emits the WebSite/WebPage(/Organization) graph, and nothing when it shouldn't. */
class StructuredDataHeadTest {

    private final FakePluginSettings config = TestSettings.declared();
    private final StructuredDataHead head = new StructuredDataHead(config, Map.of());
    private final StructuredDataHead typed = new StructuredDataHead(config, Map.ofEntries(
            Map.entry("FAQPage", new FaqPageSchema()),
            Map.entry("Product", new ProductSchema()),
            Map.entry("Article", new ArticleSchema()),
            Map.entry("BlogPosting", new ArticleSchema()),
            Map.entry("LocalBusiness", new LocalBusinessSchema()),
            Map.entry("CafeOrCoffeeShop", new LocalBusinessSchema()),
            Map.entry("Service", new ServiceSchema()),
            Map.entry("ProfessionalService", new ServiceSchema()),
            Map.entry("Person", new PersonSchema()),
            Map.entry("ItemList", new CollectionSchema()),
            Map.entry("CollectionPage", new CollectionSchema())));

    private static HeadContext pageAt(String path, String title) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta(path.substring(1), title, "",
                List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        String canonical = "https://acme.test" + path;
        return new HeadContext(site, page, Locale.ENGLISH, canonical, canonical, List.of(), false, false);
    }

    private void cfg(Map<String, Object> map) {
        config.site("acme", map);
    }

    private HeadContext ctx(boolean preview, boolean noindex) {
        SiteView site = new SiteView("acme", "Acme Corp", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("about", "About Acme", "We build things.",
                List.of(Locale.ENGLISH), Locale.ENGLISH, true, noindex);
        return new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/about",
                "https://acme.test/about", List.of(), preview, noindex);
    }

    @Test
    void emitsWebSiteAndWebPageByDefault() {
        cfg(Map.of()); // all defaults → jsonld.enabled true
        String out = head.render(ctx(false, false));
        assertTrue(out.contains("application/ld+json"));
        assertTrue(out.contains("\"@type\":\"WebSite\""));
        assertTrue(out.contains("\"@type\":\"WebPage\""));
        assertTrue(out.contains("https://acme.test/about"), "the WebPage is anchored on the canonical");
        assertTrue(out.contains("About Acme"));
        assertTrue(out.contains("\"isPartOf\""), "the WebPage references the WebSite");
    }

    @Test
    void emitsOrganizationWithSameAsWhenConfigured() {
        cfg(Map.of("org.name", "Acme Corp", "org.logo", "https://acme.test/logo.png",
                "org.sameas", List.of("https://social.example/acme", "https://network.example/acme")));
        String out = head.render(ctx(false, false));
        assertTrue(out.contains("\"@type\":\"Organization\""));
        assertTrue(out.contains("Acme Corp"));
        assertTrue(out.contains("logo.png"));
        assertTrue(out.contains("social.example/acme") && out.contains("network.example/acme"), "sameAs profiles emitted");
        assertTrue(out.contains("\"publisher\""), "the WebPage names the Organization as publisher");
    }

    @Test
    void resolvesARootRelativeOrgLogoToAbsolute() {
        cfg(Map.of("org.name", "Acme", "org.logo", "/static/img/logo.svg"));
        String out = head.render(ctx(false, false));
        assertTrue(out.contains("\"logo\":\"https://acme.test/static/img/logo.svg\""),
                "a root-relative org.logo resolves against the site base: " + out);
    }

    @Test
    void emitsArticleHeadlineWhenTypeIsArticle() {
        cfg(Map.of("jsonld.type", "Article"));
        String out = head.render(ctx(false, false));
        assertTrue(out.contains("\"@type\":\"Article\""));
        assertTrue(out.contains("\"headline\":\"About Acme\""), "Article uses headline, not name");
    }

    @Test
    void emitsNothingOnNoindexPreviewOrWhenDisabled() {
        cfg(Map.of());
        assertEquals("", head.render(ctx(false, true)), "no structured data on a noindex page");
        assertEquals("", head.render(ctx(true, false)), "no structured data on a preview render");
        cfg(Map.of("jsonld.enabled", false));
        assertEquals("", head.render(ctx(false, false)), "disabled by the tenant");
    }

    @Test
    void breadcrumbsAppearForAChildPageAndNotTheHome() {
        cfg(Map.of());
        assertTrue(head.render(ctx(false, false)).contains("\"@type\":\"BreadcrumbList\""), "child page has a trail");

        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta home = new PageMeta("index", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        HeadContext homeCtx = new HeadContext(site, home, Locale.ENGLISH, "https://acme.test/",
                "https://acme.test/", List.of(), false, false);
        assertFalse(head.render(homeCtx).contains("BreadcrumbList"), "the home page needs no breadcrumb");
    }

    // --- GEO signals, now folded into the single @graph (was AnswerSchemaHead) --------------------------

    @Test
    void foldsGeoSignalsIntoTheSameNodesWhenEnabled() {
        cfg(Map.of("geo.enabled", true, "search.url", "/search"));
        String out = head.render(ctx(false, false));
        assertEquals(1, countOccurrences(out, "application/ld+json"), "exactly one ld+json block: " + out);
        // WebSite/WebPage each defined exactly once, carrying the GEO signals inline (not a second block by @id)
        assertEquals(1, countOccurrences(out, "\"@type\":\"WebSite\""), out);
        assertEquals(1, countOccurrences(out, "\"@type\":\"WebPage\""), out);
        assertTrue(out.contains("\"potentialAction\"") && out.contains("SearchAction"), out);
        assertTrue(out.contains("\"speakable\"") && out.contains("SpeakableSpecification"), out);
        assertTrue(out.contains("{search_term_string}"), out);
    }

    @Test
    void speakableDefaultsToRealSelectorsNotPhantomArticle() {
        cfg(Map.of("geo.enabled", true));
        String out = head.render(ctx(false, false));
        assertTrue(out.contains("SpeakableSpecification"), out);
        assertTrue(out.contains("\"h1\"") && out.contains("\"main\""), out);
        assertFalse(out.contains("\"article\""), "the default no longer emits the phantom 'article' selector: " + out);
    }

    @Test
    void geoSignalsAbsentWhenGeoDisabled() {
        cfg(Map.of()); // geo.enabled default false
        String out = head.render(ctx(false, false));
        assertTrue(out.contains("\"@type\":\"WebSite\""), "the SEO graph still emits");
        assertFalse(out.contains("speakable"), "no GEO speakable when geo disabled");
        assertFalse(out.contains("SearchAction"), "no GEO SearchAction when geo disabled");
    }

    @Test
    void mapsCurlyQueryPlaceholderToSchemaVariable() {
        cfg(Map.of("geo.enabled", true, "search.url", "/find?q={query}"));
        String out = head.render(ctx(false, false));
        assertTrue(out.contains("q={search_term_string}"), out);
        assertFalse(out.contains("{query}"), "the {query} placeholder is rewritten");
    }

    // --- SoftwareApplication (item 4): homepage-only, @id-linked ---------------------------------------

    private HeadContext homeCtx(String canonical) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta home = new PageMeta("index", "Home", "The home page.",
                List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        return new HeadContext(site, home, Locale.ENGLISH, canonical, canonical, List.of(), false, false);
    }

    @Test
    void emitsSoftwareApplicationOnTheHomepageOnly() {
        cfg(Map.of("software.enabled", true, "software.name", "Acme App", "software.category", "WebApplication",
                "software.price", "0", "org.name", "Acme Corp"));
        assertFalse(head.render(ctx(false, false)).contains("SoftwareApplication"),
                "not emitted on an interior page");
        String out = head.render(homeCtx("https://acme.test/"));
        assertTrue(out.contains("\"@type\":\"SoftwareApplication\""), out);
        assertTrue(out.contains("acme.test/#software"), "carries a stable @id: " + out);
        assertTrue(out.contains("Acme App"), out);
        assertTrue(out.contains("\"@type\":\"Offer\"") && out.contains("\"price\":\"0\""), "free offer: " + out);
        assertTrue(out.contains("\"publisher\""), "references the Organization as publisher");
        assertEquals(1, countOccurrences(out, "\"@type\":\"SoftwareApplication\""), "emitted exactly once: " + out);
    }

    @Test
    void softwareApplicationAlsoFiresOnALocalizedHome() {
        cfg(Map.of("software.enabled", true, "software.name", "Acme App"));
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), "https://acme.test");
        PageMeta page = new PageMeta("index", "Inicio", "",
                List.of(Locale.ENGLISH), Locale.forLanguageTag("es"), true, false);
        HeadContext esHome = new HeadContext(site, page, Locale.forLanguageTag("es"), "https://acme.test/es",
                "https://acme.test/", List.of(), false, false);
        assertTrue(head.render(esHome).contains("SoftwareApplication"), "the localized home /es carries the entity");
    }

    @Test
    void aCrossDomainCanonicalOverrideDoesNotLeakSoftwareApplication() {
        // A per-page canonical pointing at ANOTHER host must not be mistaken for the home (which would leak the
        // homepage-only SoftwareApplication) — the pathOf() prefix guard returns a non-"/" path so isHome is false.
        cfg(Map.of("software.enabled", true, "software.name", "Acme App"));
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("syndicated", "Syndicated", "",
                List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        HeadContext ctx = new HeadContext(site, page, Locale.ENGLISH, "https://ex.test/x",
                "https://ex.test/x", List.of(), false, false);
        String out = head.render(ctx);
        assertFalse(out.contains("SoftwareApplication"), "no homepage entity on a cross-domain canonical: " + out);
        assertTrue(out.contains("\"@type\":\"WebPage\""), "the WebPage node still emits: " + out);
    }

    @Test
    void noSoftwareApplicationWhenDisabled() {
        cfg(Map.of("software.name", "Acme App")); // software.enabled defaults false
        assertFalse(head.render(homeCtx("https://acme.test/")).contains("SoftwareApplication"));
    }

    // --- type-aware structured data (item 5): FAQPage from content.schema, path-matched -----------------

    @Test
    void emitsFaqPageForAPathDeclaredInContentSchema() {
        String schema = "/faq:\n  type: FAQPage\n  faq:\n"
                + "    - { q: \"Is this real?\", a: \"No, it is a demo.\" }\n"
                + "    - { q: \"Where based?\", a: \"Portland.\" }\n";
        cfg(Map.of("content.schema", schema));
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("faq", "FAQ", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        HeadContext faqCtx = new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/faq",
                "https://acme.test/faq", List.of(), false, false);

        String out = typed.render(faqCtx);
        assertTrue(out.contains("\"@type\":\"FAQPage\""), out);
        assertTrue(out.contains("\"@type\":\"Question\"") && out.contains("\"@type\":\"Answer\""), out);
        assertTrue(out.contains("Is this real?") && out.contains("No, it is a demo."), out);
        assertTrue(out.contains("acme.test/faq#webpage"), "FAQPage is mainEntityOfPage-linked to the WebPage: " + out);
        // still ONE ld+json block — the typed node joins the same @graph
        assertEquals(1, countOccurrences(out, "application/ld+json"), out);
    }

    @Test
    void noTypedNodesWhenPathDoesNotMatchOrTypeUnregistered() {
        cfg(Map.of("content.schema", "/faq:\n  type: FAQPage\n  faq:\n    - { q: \"Q\", a: \"A\" }\n"));
        // ctx() is /about — no content.schema entry matches → no FAQPage
        assertFalse(typed.render(ctx(false, false)).contains("FAQPage"), "path /about matches no pattern");
    }

    @Test
    void emitsProductWithOfferForASectionPrefixPattern() {
        cfg(Map.of("content.schema",
                "/coffee-*:\n  type: Product\n  price: \"22.00\"\n  currency: USD\n  availability: InStock\n",
                "org.name", "Roasters"));
        String out = typed.render(pageAt("/coffee-guji", "Ethiopia Guji"));
        assertTrue(out.contains("\"@type\":\"Product\""), out);
        assertTrue(out.contains("\"@type\":\"Offer\"") && out.contains("\"price\":\"22.00\""), out);
        assertTrue(out.contains("schema.org/InStock"), "availability expanded to a URL: " + out);
        assertTrue(out.contains("\"brand\""), "brand references the Organization");
    }

    @Test
    void emitsOneLocalBusinessNodePerLocation() {
        String schema = "/cafes:\n  type: CafeOrCoffeeShop\n  locations:\n"
                + "    - { name: \"Roastery\", street: \"14 Lamplight Lane\", locality: Portland, region: OR, postalCode: \"97201\" }\n"
                + "    - { name: \"Downtown\", street: \"88 Meridian St\", locality: Portland, region: OR }\n";
        cfg(Map.of("content.schema", schema));
        String out = typed.render(pageAt("/cafes", "Cafes"));
        assertEquals(2, countOccurrences(out, "\"@type\":\"CafeOrCoffeeShop\""), "one node per location: " + out);
        assertTrue(out.contains("Roastery") && out.contains("Downtown"), out);
        assertTrue(out.contains("\"@type\":\"PostalAddress\"") && out.contains("14 Lamplight Lane"), out);
    }

    @Test
    void emitsAPersonPerTeamMember() {
        String schema = "/team:\n  type: Person\n  people:\n"
                + "    - { name: \"Theo Vance\", jobTitle: \"Head Roaster\" }\n"
                + "    - { name: \"Mara Okafor\", jobTitle: \"Cafe Director\" }\n";
        cfg(Map.of("content.schema", schema, "org.name", "Nocturne"));
        String out = typed.render(pageAt("/team", "Team"));
        assertEquals(2, countOccurrences(out, "\"@type\":\"Person\""), out);
        assertTrue(out.contains("Theo Vance") && out.contains("Mara Okafor"), out);
        assertTrue(out.contains("\"worksFor\""), "worksFor references the Organization");
    }

    // --- ItemList on a collection page ------------------------------------------------------------------

    @Test
    void emitsAnItemListForACollectionPage() {
        String schema = "/coffees:\n  type: ItemList\n  items:\n"
                + "    - { name: \"Ethiopia Guji\", url: \"/coffee-ethiopia-guji\" }\n"
                + "    - { name: \"Colombia Huila\", url: \"/coffee-colombia-huila\" }\n";
        cfg(Map.of("content.schema", schema));
        String out = typed.render(pageAt("/coffees", "Our Coffees"));
        assertTrue(out.contains("\"@type\":\"ItemList\""), out);
        // The two members appear as positioned ListItems inside itemListElement (the page's breadcrumb also uses
        // ListItem, so assert on the member payloads rather than a raw ListItem count).
        assertTrue(out.contains("Ethiopia Guji") && out.contains("Colombia Huila"), out);
        assertTrue(out.contains("https://acme.test/coffee-ethiopia-guji")
                && out.contains("https://acme.test/coffee-colombia-huila"),
                "root-relative member urls are absolutized against the base: " + out);
        assertTrue(out.contains("acme.test/coffees#itemlist"), "the ItemList carries a stable @id: " + out);
        assertTrue(out.contains("\"mainEntityOfPage\""), "the ItemList is linked to the page: " + out);
    }

    @Test
    void collectionPageAliasAlsoEmitsAnItemList() {
        cfg(Map.of("content.schema", "/index:\n  type: CollectionPage\n  items:\n    - { name: A, url: /a }\n"));
        assertTrue(typed.render(pageAt("/index", "Index")).contains("\"@type\":\"ItemList\""),
                "CollectionPage is an alias for the ItemList emitter");
    }

    // --- several type-blocks on one path ----------------------------------------------------------------

    @Test
    void emitsEveryTypeBlockWhenAPathDeclaresAList() {
        // A pricing page that is BOTH an FAQPage AND a Service — the list form yields both nodes in one @graph.
        String schema = "/pricing:\n"
                + "  - type: FAQPage\n    faq:\n      - { q: \"Why ranges?\", a: \"Scope drives cost.\" }\n"
                + "      - { q: \"Discounts?\", a: \"For non-profits.\" }\n"
                + "  - type: Service\n    name: \"Advisory Sprint\"\n    offers:\n"
                + "      - { name: \"Sprint\", price: \"18000\", currency: USD }\n";
        cfg(Map.of("content.schema", schema, "org.name", "Northbeam"));
        String out = typed.render(pageAt("/pricing", "Pricing"));
        assertTrue(out.contains("\"@type\":\"FAQPage\""), "first block emitted: " + out);
        assertTrue(out.contains("\"@type\":\"Service\""), "second block emitted: " + out);
        assertTrue(out.contains("Why ranges?") && out.contains("Advisory Sprint"), out);
        assertTrue(out.contains("\"@type\":\"Offer\"") && out.contains("\"price\":\"18000\""),
                "the priced tier is a machine-readable Offer: " + out);
        assertEquals(1, countOccurrences(out, "application/ld+json"), "still one ld+json block: " + out);
    }

    @Test
    void aRecurringServiceOfferBecomesAUnitPriceSpecification() {
        String schema = "/pricing:\n  type: Service\n  name: \"Northbeam\"\n  offers:\n"
                + "    - { name: \"Growth Retainer\", price: \"32000\", currency: USD, unit: month }\n";
        cfg(Map.of("content.schema", schema));
        String out = typed.render(pageAt("/pricing", "Pricing"));
        assertTrue(out.contains("\"@type\":\"UnitPriceSpecification\""), "a per-unit price is a spec: " + out);
        assertTrue(out.contains("\"unitText\":\"month\"") && out.contains("\"price\":\"32000\""), out);
    }

    // --- breadcrumb @id link + collection parent level --------------------------------------------------

    @Test
    void webPageLinksToTheBreadcrumbById() {
        cfg(Map.of());
        String out = head.render(ctx(false, false)); // /about — a child page
        assertTrue(out.contains("acme.test/about#breadcrumb"), "the BreadcrumbList has a stable @id: " + out);
        assertTrue(out.contains("\"breadcrumb\":{\"@id\":\"https://acme.test/about#breadcrumb\"}"),
                "the WebPage points at the BreadcrumbList by @id: " + out);
    }

    @Test
    void insertsTheDeclaredCollectionParentAsAMiddleBreadcrumbLevel() {
        // A product detail page declares its parent collection — the trail reads Home → Coffees → Guji.
        String schema = "/coffee-*:\n  type: Product\n  price: \"22.00\"\n  currency: USD\n"
                + "  breadcrumb: { name: \"Coffees\", url: \"/coffees\" }\n";
        cfg(Map.of("content.schema", schema));
        String out = typed.render(pageAt("/coffee-guji", "Ethiopia Guji"));
        assertTrue(out.contains("\"@type\":\"BreadcrumbList\""), out);
        assertEquals(3, countOccurrences(out, "\"@type\":\"ListItem\""), "Home + Coffees + the page = 3 levels: " + out);
        assertTrue(out.contains("Coffees") && out.contains("https://acme.test/coffees"),
                "the parent collection is a middle level: " + out);
        assertTrue(out.contains("\"position\":1") && out.contains("\"position\":2") && out.contains("\"position\":3"),
                "positions run 1..3: " + out);
    }

    // --- LocalBusiness @id + page link ------------------------------------------------------------------

    @Test
    void localBusinessNodesCarryAnIdAndLinkToThePage() {
        String schema = "/cafes:\n  type: CafeOrCoffeeShop\n  locations:\n"
                + "    - { name: \"Roastery\", locality: Portland }\n"
                + "    - { name: \"Downtown\", locality: Portland }\n";
        cfg(Map.of("content.schema", schema));
        String out = typed.render(pageAt("/cafes", "Cafes"));
        assertTrue(out.contains("acme.test/cafes#business-1") && out.contains("acme.test/cafes#business-2"),
                "each location gets a distinct @id: " + out);
        assertEquals(2, countOccurrences(out, "\"mainEntityOfPage\""), "each business is linked to the page: " + out);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = at + needle.length();
        }
        return count;
    }

    /**
     * The defect this fixed: {@code datePublished} and {@code author} could ONLY come from the shared
     * {@code content.schema} block, which matches by {@code *}-prefix. A blog is therefore covered by one
     * {@code /blog/*} entry for EVERY post, so a site could assert only one date and one author across all of
     * them — false of nearly every page it appeared on, which is exactly what structured-data policy prohibits.
     * Per-item provenance must win so each entry states its own truth.
     */
    @Test
    void perItemProvenanceWinsOverTheSharedContentSchemaBlock() {
        cfg(Map.of("content.schema", "/blog/*:\n  type: BlogPosting\n"
                + "  author: Shared Fallback Author\n  datePublished: 2020-01-01\n"));

        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("blog:post-1", "Real Post", "", List.of(Locale.ENGLISH), Locale.ENGLISH,
                true, false, "blog",
                new Provenance("Ada Lovelace", Instant.parse("2026-03-04T00:00:00Z"),
                        Instant.parse("2026-05-06T12:34:56Z")));
        HeadContext ctx = new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/blog/post-1",
                "https://acme.test/blog/post-1", List.of(), false, false);

        String out = typed.render(ctx);
        assertTrue(out.contains("\"name\":\"Ada Lovelace\""), "the item's own author must win: " + out);
        assertFalse(out.contains("Shared Fallback Author"), "the shared block must not override the item");
        assertTrue(out.contains("\"datePublished\":\"2026-03-04\""), "the item's own date must win: " + out);
        assertFalse(out.contains("2020-01-01"), "the shared date must not be asserted on this post");
        // dateModified comes from the host-derived updatedAt, rendered at DAY precision (not second).
        assertTrue(out.contains("\"dateModified\":\"2026-05-06\""), out);
    }

    /** With no per-item provenance the configured values still apply — existing sites keep working unchanged. */
    @Test
    void theSharedBlockStillAppliesWhenTheItemHasNoProvenance() {
        cfg(Map.of("content.schema", "/about:\n  type: Article\n"
                + "  author: Editorial Team\n  datePublished: 2024-02-03\n"));

        String out = typed.render(pageAt("/about", "About Acme"));
        assertTrue(out.contains("\"name\":\"Editorial Team\""), out);
        assertTrue(out.contains("\"datePublished\":\"2024-02-03\""), out);
    }

    /**
     * The emitted JSON-LD must be <b>byte-reproducible</b>, so the same inputs always render the same bytes.
     *
     * <p>This is a regression guard with a real history. {@code SearchAction.target} was built with
     * {@code Map.of(...)}, and {@code java.util.Map.of} randomizes iteration order <b>per JVM instance</b>
     * ({@code ImmutableCollections.SALT}) — so the key order was stable within one run and flipped on roughly
     * every other restart. An operator diffing live homepages across an upgrade found it and reasonably
     * concluded it was a one-off change between builds; it was not, it was every restart, indefinitely.
     *
     * <p>Semantically harmless — the graphs are equal key-sorted — but it made rendered output
     * non-reproducible, and the page's strong ETag folds metadata and templates rather than the body, so the
     * validator did <b>not</b> change when the bytes did.
     *
     * <p>Asserted on the exact ordered substring rather than by parsing, because parsing would compare the
     * graphs and pass under exactly the defect this exists to catch.
     */
    @Test
    void theSearchActionTargetHasADeterministicKeyOrder() {
        cfg(Map.of("geo.enabled", true, "search.url", "/search?q={query}"));
        String out = head.render(ctx(false, false));

        assertTrue(out.contains("\"target\":{\"@type\":\"EntryPoint\",\"urlTemplate\":"),
                () -> "SearchAction.target must serialize @type first, deterministically; got:\n" + out);
    }

    /** Same inputs, same bytes — the general property, asserted over repeated renders. */
    @Test
    void repeatedRendersOfTheSameInputProduceIdenticalBytes() {
        cfg(Map.of("geo.enabled", true, "search.url", "/search?q={query}"));
        String first = head.render(ctx(false, false));
        for (int i = 0; i < 20; i++) {
            assertEquals(first, head.render(ctx(false, false)), "structured data must be byte-reproducible");
        }
    }

    /**
     * A DETERMINISTIC guard that no serialized JSON-LD node is built with a multi-entry {@code Map.of(...)}.
     *
     * <h2>Why a source check and not just the behavioural test above</h2>
     * The behavioural test is the right description of intent, but it is a <b>weak</b> guard on its own,
     * because the defect it guards is probabilistic: {@code Map.of} picks one of the two orders per JVM
     * instance, so a reintroduction passes roughly half the time — and Maven reuses forked JVMs, so it can
     * pass for many consecutive runs. Measured: reverting the fix and re-running six times produced five
     * passes and one failure. A guard that reports green five times out of six is not a guard.
     *
     * <p>So this asserts the property at the only place it is reliably observable: the source. A single-entry
     * {@code Map.of("@id", …)} is fine — one key cannot be misordered — and an empty {@code Map.of()} is fine.
     * Only two-or-more entries can vary, and those must use an insertion-ordered map.
     *
     * <p>If this fails: build the node with {@code Ld.node(...)} or a {@link java.util.LinkedHashMap}. The
     * requirement is byte-reproducible output, not any particular key order.
     */
    @Test
    void noSerializedNodeIsBuiltWithAMultiEntryMapOf() throws Exception {
        for (String file : SCANNED_SOURCES) {
            java.nio.file.Path path = java.nio.file.Path.of("src/main/java/mx/quest/discover", file);
            if (!java.nio.file.Files.exists(path)) {
                continue;
            }
            String source = stripCommentsAndStrings(java.nio.file.Files.readString(path));
            int at = 0;
            while ((at = source.indexOf("Map.of(", at)) >= 0) {
                int entries = topLevelArgumentsFrom(source, at + "Map.of(".length());
                int offending = at;
                assertTrue(entries <= 2, () -> file + " builds a multi-entry Map.of near offset " + offending
                        + ", whose iteration order is randomized PER JVM INSTANCE and would make emitted "
                        + "JSON-LD non-reproducible across restarts. Use Ld.node(...) or a LinkedHashMap.");
                at += "Map.of(".length();
            }
        }
    }

    /** The sources whose output is serialized into JSON-LD, and so must be byte-reproducible. */
    private static final java.util.List<String> SCANNED_SOURCES = java.util.List.of(
            "StructuredDataHead.java", "Ld.java", "ArticleSchema.java", "ProductSchema.java",
            "LocalBusinessSchema.java", "FaqPageSchema.java", "PersonSchema.java", "ServiceSchema.java",
            "CollectionSchema.java");

    /**
     * Blanks comments and string literals, preserving offsets and newlines.
     *
     * <p>Done before scanning rather than per line, because the previous version read the file LINE BY LINE
     * and therefore only ever saw the single-line form of {@code Map.of(...)}. A call whose arguments wrap
     * onto following lines — the natural formatting for three or more entries under a 120-column limit, which
     * is precisely the case at risk — left one comma or none on the opening line and sailed through. The
     * guard was reliable only for the shape the defect was least likely to take.
     *
     * <p>Blanking rather than deleting keeps every offset intact, so the failure message can point at a real
     * position in the file. Strings are blanked so a {@code Map.of} mentioned inside one, or a comma inside a
     * string argument, cannot be miscounted.
     */
    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                while (i < source.length() && !(source.charAt(i) == '*' && i + 1 < source.length()
                        && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                for (int k = 0; k < 2 && i < source.length(); k++, i++) {
                    out.append(' ');
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < source.length() && source.charAt(i) != quote) {
                    if (source.charAt(i) == '\\') {
                        out.append(' ');
                        i++;
                    }
                    if (i < source.length()) {
                        out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                }
                if (i < source.length()) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** How many top-level arguments the call opening at {@code from} has (0 when it is empty). */
    private static int topLevelArgumentsFrom(String source, int from) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return any ? commas + 1 : 0;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
            if (!Character.isWhitespace(c)) {
                any = true;
            }
        }
        return any ? commas + 1 : 0;
    }

    /**
     * The guard can actually fail — proven against synthetic sources rather than hoped for.
     *
     * <p>A source-level scan that silently matches nothing is indistinguishable from a codebase that is
     * clean, and this one WAS partly that: it could not see a wrapped call. So the scanner's two halves are
     * exercised directly, including the multi-line form it used to miss.
     */
    @Test
    void theMapOfScannerCatchesBothTheSingleLineAndTheWrappedForm() {
        String singleLine = "var x = Map.of(\"a\", 1, \"b\", 2);";
        String wrapped = "var x = Map.of(\n    \"a\", 1,\n    \"b\", 2,\n    \"c\", 3);";
        String safe = "var x = Map.of(\"only\", 1);";
        String inAComment = "// Map.of(\"a\", 1, \"b\", 2) is what NOT to do";
        String inAString = "var s = \"Map.of(a, 1, b, 2)\";";

        assertEquals(4, argumentsOfFirstMapOf(singleLine), "two entries = four arguments");
        assertEquals(6, argumentsOfFirstMapOf(wrapped), "the wrapped form is the one it used to miss");
        assertEquals(2, argumentsOfFirstMapOf(safe), "a single entry is fine");
        assertEquals(-1, argumentsOfFirstMapOf(inAComment), "prose explaining the rule is not a violation");
        assertEquals(-1, argumentsOfFirstMapOf(inAString), "nor is a string that happens to contain it");
    }

    /** Arguments of the first {@code Map.of(} in {@code source} after stripping, or {@code -1} if none. */
    private static int argumentsOfFirstMapOf(String source) {
        String stripped = stripCommentsAndStrings(source);
        int at = stripped.indexOf("Map.of(");
        return at < 0 ? -1 : topLevelArgumentsFrom(stripped, at + "Map.of(".length());
    }

    /**
     * The declared head input names the very file this contributor reads.
     *
     * <p>{@code headInputs} is answered without any storage layout, so the path is a literal — and a literal
     * that does not match what the kernel reads folds a stable "absent" marker forever. The validator then
     * looks correct while covering nothing, and the symptom is a stale social card or organisation name on
     * every cached client, weeks later, with nothing pointing back here.
     *
     * <p>The kernel pins the other half of the convention ({@code <site>/plugins/<id>.yml}); this pins that
     * the id in the path is the id this contributor actually looks up its settings under, so renaming the
     * plugin cannot leave the declaration behind.
     */
    @Test
    void theDeclaredHeadInputIsThisPluginsOwnSettingsFile() {
        assertEquals(List.of("plugins/" + StructuredDataHead.PLUGIN_ID + ".yml"), head.headInputs("acme"));
        assertEquals(List.of("plugins/discoverability.yml"), head.headInputs("acme"),
                "the kernel reads <site>/plugins/<id>.yml — see SitePluginConfigServiceTest");
    }

    // --- a host-less site: absolute URLs from HeadContext.origin() (SPI 1.0.0, discoverability 2.1.0) ------------

    /** A host-less site (no base URL, no host name — the platform's catch-all default): its canonical is relative. */
    private static HeadContext hostless(String path, String title, String origin) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "");
        PageMeta page = new PageMeta(path.substring(1), title, "", List.of(Locale.ENGLISH), Locale.ENGLISH, true,
                false);
        return new HeadContext(site, page, Locale.ENGLISH, path, path, List.of(), false, false, null, null, origin);
    }

    @Test
    void aHostlessSitesGraphIsAbsoluteAgainstTheTrustedOrigin() {
        cfg(Map.of("org.name", "Acme", "org.logo", "/static/logo.svg"));
        String out = head.render(hostless("/about", "About", "http://10.0.0.5:8080"));
        assertTrue(out.contains("\"@id\":\"http://10.0.0.5:8080/#website\""), out);
        assertTrue(out.contains("\"url\":\"http://10.0.0.5:8080/\""), "WebSite/Organization url: " + out);
        assertTrue(out.contains("\"@id\":\"http://10.0.0.5:8080/about#webpage\""), out);
        assertTrue(out.contains("\"url\":\"http://10.0.0.5:8080/about\""), out);
        assertTrue(out.contains("\"logo\":\"http://10.0.0.5:8080/static/logo.svg\""), out);
        assertTrue(out.contains("\"@type\":\"BreadcrumbList\""), "a host-less deep page now has its trail: " + out);
        assertTrue(out.contains("\"item\":\"http://10.0.0.5:8080/\""), out);
        assertFalse(out.contains("\":\"/"), "no root-relative URL or @id is left: " + out);
    }

    @Test
    void aHostlessSiteGainsItsSearchActionWithAnAbsoluteTemplate() {
        cfg(Map.of("geo.enabled", true, "search.url", "/search?q={query}"));
        String out = head.render(hostless("/about", "About", "https://example.test"));
        assertTrue(out.contains("\"urlTemplate\":\"https://example.test/search?q={search_term_string}\""), out);
    }

    @Test
    void theHostlessHomeIsRecognisedAndCarriesNoTrail() {
        cfg(Map.of("software.enabled", true));
        String out = head.render(hostless("/", "Home", "https://example.test"));
        assertTrue(out.contains("\"@type\":\"SoftwareApplication\""), "the home is still the home: " + out);
        assertTrue(out.contains("\"@id\":\"https://example.test/#webpage\""), out);
        assertFalse(out.contains("BreadcrumbList"), out);
    }

    /** A context with no origin (the constructor without one, or no request) renders exactly as 2.0.0 did. */
    @Test
    void withoutAnOriginAHostlessGraphStaysRootRelativeAsBefore() {
        cfg(Map.of("geo.enabled", true, "search.url", "/search"));
        String out = head.render(hostless("/about", "About", ""));
        assertTrue(out.contains("\"@id\":\"/about#webpage\""), out);
        assertTrue(out.contains("\"@id\":\"/#website\""), out);
        assertFalse(out.contains("SearchAction"), "no base, no SearchAction — as 2.0.0: " + out);
        assertFalse(out.contains("BreadcrumbList"), out);
    }

    /** The site's base URL wins over the request origin: a site with a host never takes the address it was reached at. */
    @Test
    void aConfiguredBaseUrlWinsOverTheOrigin() {
        cfg(Map.of());
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("about", "About", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        HeadContext ctx = new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/about",
                "https://acme.test/about", List.of(), false, false, null, null, "http://10.0.0.5:8080");
        String out = head.render(ctx);
        assertTrue(out.contains("https://acme.test/#website"), out);
        assertFalse(out.contains("10.0.0.5"), out);
        HeadContext noOrigin = new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/about",
                "https://acme.test/about", List.of(), false, false);
        assertEquals(head.render(noOrigin), out, "byte-identical to the origin-less render of the same page");
    }

    /** A cross-domain canonical override is absolute already and passes through untouched. */
    @Test
    void anAbsoluteCanonicalIsNeverRebased() {
        assertEquals("https://other.test/x", StructuredDataHead.absolute("https://other.test/x", "http://a.test"));
        assertEquals("//cdn.test/x", StructuredDataHead.absolute("//cdn.test/x", "http://a.test"),
                "a protocol-relative URL already names a host");
        assertEquals("/x", StructuredDataHead.absolute("/x", ""), "no base known: left as it is");
        assertEquals("http://a.test/x", StructuredDataHead.absolute("/x", "http://a.test"));
    }
}
