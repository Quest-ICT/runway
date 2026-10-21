package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Immutability, defensive copying, and null-normalization across the SPI boundary records. */
class BoundaryRecordsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale FR = Locale.FRENCH;

    private static SiteView site() {
        return new SiteView("s1", "Site", EN, List.of(EN), "https://example.test");
    }

    private static PageMeta page() {
        return new PageMeta("home", "Home", "A page", List.of(EN), EN, true, false);
    }

    // --- SiteView ---

    @Test
    void siteViewNullLocalesNormalizeToEmpty() {
        assertTrue(new SiteView("s", "n", EN, null, null).locales().isEmpty());
    }

    @Test
    void siteViewLocalesUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> site().locales().add(FR));
    }

    @Test
    void siteViewDefensivelyCopiesLocales() {
        List<Locale> source = new ArrayList<>(List.of(EN));
        SiteView s = new SiteView("s", "n", EN, source, null);
        source.add(FR);
        assertEquals(1, s.locales().size());
    }

    @Test
    void siteViewRequiresIdNameDefaultLocale() {
        assertThrows(NullPointerException.class, () -> new SiteView(null, "n", EN, List.of(), null));
        assertThrows(NullPointerException.class, () -> new SiteView("s", null, EN, List.of(), null));
        assertThrows(NullPointerException.class, () -> new SiteView("s", "n", null, List.of(), null));
    }

    @Test
    void siteViewBaseUrlIsOptionalAndStored() {
        assertNull(new SiteView("s", "n", EN, List.of(), null).baseUrl());
        assertEquals("https://example.test", site().baseUrl());
    }

    @Test
    void siteViewRefCarriesId() {
        assertEquals("s1", site().ref().id());
    }

    // --- SiteRef / Alternate ---

    @Test
    void siteRefRequiresId() {
        assertThrows(NullPointerException.class, () -> new SiteRef(null));
    }

    @Test
    void alternateRequiresLocaleAndUrl() {
        assertThrows(NullPointerException.class, () -> new Alternate(null, "/x"));
        assertThrows(NullPointerException.class, () -> new Alternate(EN, null));
    }

    // --- PageMeta ---

    @Test
    void pageMetaNullTextNormalizesToEmpty() {
        PageMeta p = new PageMeta("s", null, null, null, null, false, false);
        assertEquals("", p.title());
        assertEquals("", p.description());
    }

    @Test
    void pageMetaNullLocalesNormalizeToEmpty() {
        assertTrue(new PageMeta("s", null, null, null, null, false, false).availableLocales().isEmpty());
    }

    @Test
    void pageMetaPrimaryLocaleOptionalAndFlags() {
        assertNull(new PageMeta("s", "t", "d", List.of(), null, false, false).primaryLocale());
        assertEquals(EN, page().primaryLocale());
        assertTrue(page().published());
        assertFalse(page().noindex());
    }

    @Test
    void pageMetaRequiresSlug() {
        assertThrows(NullPointerException.class, () -> new PageMeta(null, "t", "d", List.of(), EN, true, false));
    }

    @Test
    void pageMetaLocalesUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> page().availableLocales().add(FR));
    }

    // --- HeadContext ---

    @Test
    void headContextNullAlternatesNormalizeToEmpty() {
        HeadContext h = new HeadContext(site(), page(), EN, null, null, null, false, false);
        assertTrue(h.alternates().isEmpty());
    }

    @Test
    void headContextAlternatesUnmodifiableAndCopied() {
        List<Alternate> source = new ArrayList<>();
        source.add(new Alternate(EN, "/x"));
        HeadContext h = new HeadContext(site(), page(), EN, null, null, source, false, false);
        source.add(new Alternate(FR, "/y"));
        assertEquals(1, h.alternates().size());
        assertThrows(UnsupportedOperationException.class, () -> h.alternates().add(new Alternate(FR, "/z")));
    }

    @Test
    void headContextRequiresSitePageLocale() {
        assertThrows(NullPointerException.class, () -> new HeadContext(null, page(), EN, null, null, null, false, false));
        assertThrows(NullPointerException.class, () -> new HeadContext(site(), null, EN, null, null, null, false, false));
        assertThrows(NullPointerException.class, () -> new HeadContext(site(), page(), null, null, null, null, false, false));
    }

    @Test
    void headContextAllowsNullCanonicalAndXDefault() {
        HeadContext h = new HeadContext(site(), page(), EN, null, null, null, false, false);
        assertNull(h.canonical());
        assertNull(h.xDefault());
        // base URL is reached through the site, not duplicated on the context
        assertEquals("https://example.test", h.site().baseUrl());
    }

    // --- WidgetView ---

    @Test
    void widgetViewNullConfigNormalizesToEmpty() {
        assertTrue(new WidgetView(site(), page(), EN, "inst", false, null).config().isEmpty());
    }

    @Test
    void widgetViewConfigUnmodifiableAndCopied() {
        Map<String, Object> source = new HashMap<>();
        source.put("k", "v");
        WidgetView w = new WidgetView(site(), page(), EN, "inst", false, source);
        source.put("k2", "v2");
        assertEquals(1, w.config().size());
        assertThrows(UnsupportedOperationException.class, () -> w.config().put("a", "b"));
    }

    @Test
    void widgetViewChromeFlagStored() {
        assertTrue(new WidgetView(site(), page(), EN, "inst", true, Map.of()).isChrome());
        assertFalse(new WidgetView(site(), page(), EN, "inst", false, Map.of()).isChrome());
    }

    @Test
    void widgetViewRequiresInstanceId() {
        assertThrows(NullPointerException.class, () -> new WidgetView(site(), page(), EN, null, false, Map.of()));
    }

    // --- PageView ---

    @Test
    void pageViewNullHtmlNormalizesToEmpty() {
        assertEquals("", new PageView(site(), page(), EN, null, null).resolvedHtml());
    }

    @Test
    void pageViewCanonicalOptional() {
        assertNull(new PageView(site(), page(), EN, null, "<html>").canonical());
        assertEquals("https://example.test/home", new PageView(site(), page(), EN, "https://example.test/home", "<html>").canonical());
    }

    @Test
    void pageViewRequiresSite() {
        assertThrows(NullPointerException.class, () -> new PageView(null, page(), EN, null, "<html>"));
    }

    // --- Route ---

    @Test
    void routeNullPostureBecomesSafeDefault() {
        assertEquals(SurfacePosture.safeDefault(), new Route("GET", "/x", Realm.TENANT, null).posture());
    }

    @Test
    void routeRequiresMethodPathRealm() {
        assertThrows(NullPointerException.class, () -> new Route(null, "/x", Realm.TENANT, null));
        assertThrows(NullPointerException.class, () -> new Route("GET", null, Realm.TENANT, null));
        assertThrows(NullPointerException.class, () -> new Route("GET", "/x", null, null));
    }

    @Test
    void routeGetFactory() {
        Route r = Route.get("/sitemap.xml", Realm.APPLICATION);
        assertEquals("GET", r.method());
        assertEquals(Realm.APPLICATION, r.realm());
        assertEquals(SurfacePosture.safeDefault(), r.posture());
    }

    // --- RouteRequest ---

    @Test
    void routeRequestCarriesFullSiteView() {
        RouteRequest req = new RouteRequest(site(), EN, "GET", "/robots.txt", null, null, null);
        assertEquals("https://example.test", req.site().baseUrl());
        assertEquals("s1", req.site().id());
    }

    @Test
    void routeRequestBodyCopiedOnInput() {
        byte[] source = {1, 2, 3};
        RouteRequest req = new RouteRequest(site(), EN, "GET", "/x", null, null, source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, req.body());
    }

    @Test
    void routeRequestBodyCopiedOnAccess() {
        RouteRequest req = new RouteRequest(site(), EN, "GET", "/x", null, null, new byte[] {1, 2, 3});
        req.body()[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, req.body());
    }

    @Test
    void routeRequestNullBodyBecomesEmpty() {
        assertEquals(0, new RouteRequest(site(), EN, "GET", "/x", null, null, null).body().length);
    }

    @Test
    void routeRequestQueryDeeplyCopiedAndUnmodifiable() {
        Map<String, List<String>> query = new HashMap<>();
        query.put("k", new ArrayList<>(List.of("v")));
        RouteRequest req = new RouteRequest(site(), EN, "GET", "/x", query, null, null);
        query.get("k").add("v2");
        query.put("k2", List.of("x"));
        assertEquals(1, req.query().size());
        assertEquals(1, req.query().get("k").size());
        assertThrows(UnsupportedOperationException.class, () -> req.query().put("z", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> req.query().get("k").add("w"));
    }

    @Test
    void routeRequestHeadersUnmodifiable() {
        RouteRequest req = new RouteRequest(site(), EN, "GET", "/x", null, Map.of("h", "v"), null);
        assertThrows(UnsupportedOperationException.class, () -> req.headers().put("a", "b"));
    }

    @Test
    void routeRequestRequiresSiteLocaleMethodPath() {
        assertThrows(NullPointerException.class, () -> new RouteRequest(null, EN, "GET", "/x", null, null, null));
        assertThrows(NullPointerException.class, () -> new RouteRequest(site(), null, "GET", "/x", null, null, null));
        assertThrows(NullPointerException.class, () -> new RouteRequest(site(), EN, null, "/x", null, null, null));
        assertThrows(NullPointerException.class, () -> new RouteRequest(site(), EN, "GET", null, null, null, null));
    }

    // --- RouteResponse ---

    @Test
    void routeResponseBodyCopiedInAndOut() {
        byte[] source = {1, 2};
        RouteResponse resp = new RouteResponse(200, null, source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, resp.body());
        resp.body()[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, resp.body());
    }

    @Test
    void routeResponseNullHeadersBecomeEmpty() {
        assertTrue(new RouteResponse(200, null, null).headers().isEmpty());
    }

    // --- SPI 0.3.1: PageMeta.sourceKey + WidgetView.path (old-arity ctors are binary API) -------------------

    @org.junit.jupiter.api.Test
    void pageMetaOldArityYieldsAKernelPage() {
        PageMeta meta = new PageMeta("about", "About", "", java.util.List.of(), null, true, false);
        org.junit.jupiter.api.Assertions.assertNull(meta.sourceKey(), "old-arity ⇒ kernel page");
        org.junit.jupiter.api.Assertions.assertFalse(meta.isVirtual());
    }

    @org.junit.jupiter.api.Test
    void pageMetaWithASourceKeyIsVirtualAndKeepsTheCompositeSlug() {
        PageMeta meta = new PageMeta("blog:my-post", "My post", "", java.util.List.of(), null, true, false, "blog");
        org.junit.jupiter.api.Assertions.assertTrue(meta.isVirtual());
        org.junit.jupiter.api.Assertions.assertEquals("blog", meta.sourceKey());
        org.junit.jupiter.api.Assertions.assertEquals("blog:my-post", meta.slug(), "the composite id is accepted verbatim");
    }

    @org.junit.jupiter.api.Test
    void widgetViewPathNormalizesNullAndOldArityCtorsMaterializeEmpties() {
        SiteView site = new SiteView("acme", "Acme", java.util.Locale.ENGLISH, java.util.List.of(), null);
        PageMeta page = new PageMeta("home", "", "", java.util.List.of(), null, true, false);
        WidgetView full = new WidgetView(site, page, java.util.Locale.ENGLISH, "i", false,
                java.util.Map.of(), java.util.Map.of(), null);
        org.junit.jupiter.api.Assertions.assertEquals("", full.path(), "null path normalizes to empty");
        WidgetView preQuery = new WidgetView(site, page, java.util.Locale.ENGLISH, "i", false, java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertEquals("", preQuery.path());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of(), preQuery.query());
        WidgetView prePath = new WidgetView(site, page, java.util.Locale.ENGLISH, "i", false,
                java.util.Map.of(), java.util.Map.of("q", "x"));
        org.junit.jupiter.api.Assertions.assertEquals("", prePath.path());
        org.junit.jupiter.api.Assertions.assertEquals("x", prePath.query().get("q"));
    }
}
