package mx.quest.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The demo's axis implementations as pure functions of their SPI inputs: no Spring, no host — the services come from
 * {@code runway-spi-testkit}, whose fakes apply the kernel's input rules.
 */
class DemoSpiUnitTest {

    private static final SiteView SITE = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH),
            "https://acme.test");
    private static final PageMeta PAGE = new PageMeta("about", "About us", "A description",
            List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);

    @TempDir
    Path sites;
    private DemoNotesStore notes;
    private FakeSiteServices kernel;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        notes = new DemoNotesStore(new TempDirSiteFiles(sites, "runway-demo"));
        kernel = new FakeSiteServices().site(SITE);
    }

    private static FakePluginSettings settings(Map<String, ?> stored) {
        return new FakePluginSettings().site("acme", stored);
    }

    /** A site's CAPTCHA: inactive (the kernel's default), or active and accepting one token only. */
    static CaptchaService captcha(boolean active, String accepted) {
        return new CaptchaService() {
            @Override
            public CaptchaWidget widgetForSite(String siteId) {
                return active ? new CaptchaWidget(true, "g-recaptcha-response", "<div class=\"challenge\"></div>")
                        : CaptchaWidget.inactive();
            }

            @Override
            public boolean verify(String siteId, String token, String clientIp) {
                return !active || accepted.equals(token);
            }
        };
    }

    // --- HeadContributor -------------------------------------------------------------------------------

    @Test
    void headEmitsGeneratorAndGatesAnalyticsOnTheSiteFlagAndTheAppKey() {
        DemoAppProps props = new DemoAppProps();
        props.getGenerator().setLabel("My Site");
        props.getAnalytics().setWriteKey("ENC(opaque)");
        HeadContext ctx = new HeadContext(SITE, PAGE, Locale.ENGLISH, "https://acme.test/about", null,
                List.of(), false, false);
        String on = new DemoAnalyticsHead(props, settings(Map.of("analytics.enabled", true))).render(ctx);
        assertTrue(on.contains("<meta name=\"generator\" content=\"My Site\">"), on);
        assertTrue(on.contains("data-demo-analytics") && on.contains("demo-analytics.js"), on);
        assertFalse(on.contains("ENC(opaque)"), "the key never reaches the page");

        String off = new DemoAnalyticsHead(props, settings(Map.of())).render(ctx);
        assertFalse(off.contains("data-demo-analytics"), "analytics script absent when the site flag is off");
        assertTrue(off.contains("demo.css"), "stylesheet linked (the notice defaults on)");

        DemoAppProps noKey = new DemoAppProps();
        assertFalse(new DemoAnalyticsHead(noKey, settings(Map.of("analytics.enabled", true))).render(ctx)
                .contains("data-demo-analytics"), "no app-level key, no script");
        assertFalse(new DemoAnalyticsHead(props, settings(Map.of("notice.enabled", false))).render(ctx)
                .contains("demo.css"), "notice off, no stylesheet");
    }

    @Test
    void headEscapesTheGeneratorLabelAndDeclaresItsSettingsFile() {
        DemoAppProps props = new DemoAppProps();
        props.getGenerator().setLabel("<script>x</script>");
        HeadContext ctx = new HeadContext(SITE, PAGE, Locale.ENGLISH, null, null, List.of(), false, false);
        DemoAnalyticsHead head = new DemoAnalyticsHead(props, settings(Map.of()));
        String out = head.render(ctx);
        assertTrue(out.contains("&lt;script&gt;"), "label escaped");
        assertFalse(out.contains("<script>x</script>"), "raw label not emitted");
        assertEquals(List.of("plugins/runway-demo.yml"), head.headInputs("acme"));
    }

    // --- ContentWidget: notice -------------------------------------------------------------------------

    @Test
    void noticeRendersWhenEnabledAndIsEmptyWhenDisabled() {
        WidgetView view = new WidgetView(SITE, PAGE, Locale.ENGLISH, "n1", false, Map.of());
        RenderFragment f = new DemoNoticeWidget(new DemoAppProps(),
                settings(Map.of("notice.enabled", true, "notice.style", "warning")), kernel).render(view);
        assertFalse(f.isEmpty());
        assertTrue(f.html().contains("rw-demo-notice-warning"), f.html());
        assertTrue(f.html().contains("instance &quot;n1&quot;") && f.html().contains("site &quot;Acme&quot;"),
                "the fallback text formats its arguments: " + f.html());

        assertTrue(new DemoNoticeWidget(new DemoAppProps(), settings(Map.of("notice.enabled", false)), kernel)
                .render(view).isEmpty(), "disabled notice renders empty");
    }

    @Test
    void noticeUsesTheSitesMessageInTheVisitorsLocaleAndEscapesIt() {
        kernel.message("acme", "runwaydemo.notice.body", "<b>Aviso</b> {0} en {1}");
        WidgetView view = new WidgetView(SITE, PAGE, Locale.of("es"), "n1", false, Map.of());
        String html = new DemoNoticeWidget(new DemoAppProps(), settings(Map.of()), kernel).render(view).html();
        assertTrue(html.contains("&lt;b&gt;Aviso&lt;/b&gt; n1 en Acme"), html);
    }

    @Test
    void noticeFallsBackToInfoForAnUnknownStyleAndClampsMaxChars() {
        WidgetView view = new WidgetView(SITE, PAGE, Locale.ENGLISH, "n1", false, Map.of());
        String bad = new DemoNoticeWidget(new DemoAppProps(), settings(Map.of("notice.style", "x\" onclick=\"y")),
                kernel).render(view).html();
        assertTrue(bad.contains("rw-demo-notice-info\""), bad);
        String truncated = new DemoNoticeWidget(new DemoAppProps(), settings(Map.of("notice.max-chars", 10L)), kernel)
                .render(view).html();
        assertTrue(truncated.contains(">This is th…<"), "truncated with an ellipsis: " + truncated);
        String clamped = new DemoNoticeWidget(new DemoAppProps(), settings(Map.of("notice.max-chars", -5L)), kernel)
                .render(view).html();
        assertTrue(clamped.contains(">T…<"), "a hand-edited negative cap is clamped to 1: " + clamped);
    }

    @Test
    void aChromeNoticeShowsTheAppLevelBanner() {
        DemoAppProps props = new DemoAppProps();
        props.getBanner().setText("Maintenance tonight");
        WidgetView chrome = new WidgetView(SITE, PAGE, Locale.ENGLISH, "top", true, Map.of());
        assertTrue(new DemoNoticeWidget(props, settings(Map.of()), kernel).render(chrome).html()
                .contains("Maintenance tonight"));
        props.getBanner().setEnabled(false);
        assertFalse(new DemoNoticeWidget(props, settings(Map.of()), kernel).render(chrome).html()
                .contains("Maintenance tonight"), "a disabled banner leaves the ordinary notice text");
    }

    // --- ContentWidget: feedback form ------------------------------------------------------------------

    private static WidgetView feedbackView(Map<String, String> query, String path) {
        return new WidgetView(SITE, PAGE, Locale.ENGLISH, "page", false, Map.of(), query, path);
    }

    @Test
    void feedbackFormPostsToTheAnonymousRouteWithReturnPathHoneypotAndNoCsrfField() {
        String html = new DemoFeedbackWidget(settings(Map.of()), kernel, captcha(false, "")).render(feedbackView(Map.of(), "/demo"))
                .html();
        assertTrue(html.contains("<form method=\"post\" action=\"/demo/feedback\">"), html);
        assertTrue(html.contains("name=\"return\" value=\"/demo\""), html);
        assertTrue(html.contains("name=\"message\""), html);
        assertTrue(html.contains("name=\"website\"") && html.contains("rw-demo-hp"), "honeypot present");
        assertFalse(html.contains("_csrf"), "a shared, cacheable page carries no per-session token");
        assertFalse(html.contains("role=\"status\""), "no outcome without the flag");
        assertFalse(html.contains("challenge"), "no CAPTCHA markup when the site has none");
    }

    @Test
    void feedbackFormCarriesTheSitesChallengeWhenItHasOne() {
        DemoFeedbackWidget widget = new DemoFeedbackWidget(settings(Map.of()), kernel, captcha(true, "solved"));
        String html = widget.render(feedbackView(Map.of(), "/demo")).html();
        assertTrue(html.indexOf("<div class=\"challenge\"></div>") > html.indexOf("<form")
                && html.indexOf("<div class=\"challenge\"></div>") < html.indexOf("</form>"), html);
        assertTrue(widget.render(feedbackView(Map.of("demo-feedback", "captcha"), "/demo")).html()
                .contains("Please complete the challenge and try again."));
    }

    @Test
    void feedbackShowsTheOutcomeFlagAndEscapesTheReturnPath() {
        DemoFeedbackWidget widget = new DemoFeedbackWidget(settings(Map.of()), kernel, captcha(false, ""));
        assertTrue(widget.render(feedbackView(Map.of("demo-feedback", "thanks"), "/x")).html()
                .contains("Thank you"));
        assertTrue(widget.render(feedbackView(Map.of("demo-feedback", "invalid"), "/x")).html()
                .contains("Please write a message first."));
        assertTrue(widget.render(feedbackView(Map.of("demo-feedback", "limited"), "/x")).html()
                .contains("Too many messages"));
        assertFalse(widget.render(feedbackView(Map.of("demo-feedback", "<b>"), "/x")).html()
                .contains("role=\"status\""), "an unknown flag shows nothing");
        String html = widget.render(feedbackView(Map.of(), "/a\"b")).html();
        assertTrue(html.contains("value=\"/a&quot;b\""), html);
        assertTrue(widget.render(feedbackView(Map.of(), "")).html().contains("name=\"return\" value=\"/\""),
                "an unknown path returns to the home page");
    }

    @Test
    void feedbackIsEmptyWhenTheSiteTurnedItOff() {
        assertTrue(new DemoFeedbackWidget(settings(Map.of("feedback.enabled", false)), kernel,
                captcha(true, "solved"))
                .render(feedbackView(Map.of(), "/demo")).isEmpty());
    }

    // --- PageQualityCheck ------------------------------------------------------------------------------

    @Test
    void qualityFlagsLongTitleMissingDescriptionAndMissingH1() {
        PageMeta longTitle = new PageMeta("p", "x".repeat(80), "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        PageView page = new PageView(SITE, longTitle, Locale.ENGLISH, "u", "<html><body>no heading</body></html>");
        List<String> codes = new DemoMetaLengthCheck(settings(Map.of())).inspect(page).stream()
                .map(Finding::code).toList();
        assertEquals(List.of("title.too-long", "description.missing", "h1.missing"), codes);
    }

    @Test
    void qualityRatioScalesTheTitleLimitAndIgnoreCodesSuppress() {
        PageMeta title70 = new PageMeta("p", "x".repeat(70), "d", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        PageView page = new PageView(SITE, title70, Locale.ENGLISH, "u", "<h1>t</h1>");
        assertTrue(new DemoMetaLengthCheck(settings(Map.of("quality.title-warn-ratio", "1.5"))).inspect(page)
                .isEmpty(), "70 chars is under 60 × 1.5");
        assertEquals(1, new DemoMetaLengthCheck(settings(Map.of())).inspect(page).size());
        PageView noH1 = new PageView(SITE, title70, Locale.ENGLISH, "u", "<p>no h1</p>");
        List<String> codes = new DemoMetaLengthCheck(settings(Map.of("quality.ignore-codes",
                "title.too-long, h1.missing"))).inspect(noH1).stream().map(Finding::code).toList();
        assertTrue(codes.isEmpty(), "a comma-separated ignore list (with spaces) suppresses both: " + codes);
    }

    @Test
    void qualityCleanPageHasNoFindings() {
        PageView page = new PageView(SITE, PAGE, Locale.ENGLISH, "u", "<html><H1>About</H1></html>");
        assertTrue(new DemoMetaLengthCheck(settings(Map.of())).inspect(page).isEmpty());
    }

    // --- EndpointContributor ---------------------------------------------------------------------------

    private DemoEndpoints endpoints(FakePluginSettings settings, AtomicLong clock) {
        return endpoints(settings, clock, captcha(false, ""));
    }

    private DemoEndpoints endpoints(FakePluginSettings settings, AtomicLong clock, CaptchaService captcha) {
        return new DemoEndpoints(kernel, notes, kernel, settings, captcha, clock::get);
    }

    private static RouteRequest get(String path) {
        return new RouteRequest(SITE, Locale.ENGLISH, "GET", path, Map.of("q", List.of("1")),
                Map.of("X-Test", "yes"), null, null, "203.0.113.9", "https://acme.test");
    }

    private static RouteRequest post(String path, Map<String, List<String>> form, String clientIp) {
        return new RouteRequest(SITE, Locale.ENGLISH, "POST", path, Map.of(), Map.of(), null, form, clientIp,
                "https://acme.test");
    }

    private static String body(RouteResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void routesAreThreeGetsAndOneAnonymousPost() {
        List<Route> routes = endpoints(settings(Map.of()), new AtomicLong()).routes();
        assertEquals(List.of("GET /demo/hello.txt", "GET /demo/echo.json", "GET /demo/notes.json",
                "POST /demo/feedback"), routes.stream().map(r -> r.method() + " " + r.pathPattern()).toList());
        assertEquals(List.of(false, false, false, true), routes.stream().map(Route::anonymous).toList(),
                "only the visitor form is anonymous");
    }

    @Test
    void helloIsLocalizedForTheRequestLocale() {
        kernel.message("acme", "runwaydemo.hello", "¡Hola!");
        RouteResponse hello = endpoints(settings(Map.of()), new AtomicLong()).handle(get("/demo/hello.txt"));
        assertEquals(200, hello.status());
        assertTrue(body(hello).startsWith("¡Hola!\nsite: Acme (acme)"), body(hello));
        assertTrue(body(hello).contains("origin: https://acme.test"), body(hello));
    }

    @Test
    void echoShowsWhatTheHostPutInTheRequestWithLowercaseHeaderNames() {
        RouteResponse echo = endpoints(settings(Map.of()), new AtomicLong()).handle(get("/demo/echo.json"));
        assertEquals(200, echo.status());
        String json = body(echo);
        assertTrue(json.contains("\"path\":\"/demo/echo.json\""), json);
        // Since SPI 0.5.0 the host hands header names in lower case (lookups ignore case).
        assertTrue(json.contains("\"x-test\":\"yes\""), "headers echoed by their lowercase name: " + json);
        assertFalse(json.contains("X-Test"), json);
        assertTrue(json.contains("\"clientIp\":\"203.0.113.9\"") && json.contains("\"origin\":\"https://acme.test\""),
                json);
        assertTrue(json.contains("\"form\":{}") && json.contains("\"param.q\":\"1\""), json);
    }

    @Test
    void notesEndpointIsPermissionGated() {
        DemoEndpoints ep = endpoints(settings(Map.of()), new AtomicLong());
        assertEquals(403, ep.handle(get("/demo/notes.json")).status(), "denied without the permission");
        kernel.grant("runway-demo|manage-notes");
        RouteResponse ok = ep.handle(get("/demo/notes.json"));
        assertEquals(200, ok.status());
        assertTrue(body(ok).contains("\"count\":1"), "the welcome note: " + body(ok));
    }

    @Test
    void aWrongMethodOrUnknownPathIsNotFound() {
        DemoEndpoints ep = endpoints(settings(Map.of()), new AtomicLong());
        assertEquals(404, ep.handle(post("/demo/hello.txt", Map.of(), null)).status());
        assertEquals(404, ep.handle(get("/demo/feedback")).status(), "a GET submits nothing");
        assertEquals(404, ep.handle(get("/demo/other")).status());
    }

    @Test
    void feedbackStoresTheMessageAsAVisitorNoteAndRedirectsWithOnlyTheOutcome() {
        DemoEndpoints ep = endpoints(settings(Map.of()), new AtomicLong());
        RouteResponse r = ep.handle(post("/demo/feedback", Map.of("message", List.of("  Nice site!  "),
                "return", List.of("/demo"), "website", List.of("")), "198.51.100.1"));
        assertEquals(303, r.status());
        assertEquals("/demo?demo-feedback=thanks", r.headers().get("Location"));
        Note last = notes.list("acme").getLast();
        assertEquals("Nice site!", last.text());
        assertEquals(Note.ORIGIN_VISITOR, last.origin());
    }

    @Test
    void feedbackHoneypotBlankAndForeignReturnAreHandled() {
        DemoEndpoints ep = endpoints(settings(Map.of()), new AtomicLong());
        int before = notes.count("acme");
        RouteResponse bot = ep.handle(post("/demo/feedback", Map.of("message", List.of("spam"),
                "website", List.of("http://spam")), "198.51.100.2"));
        assertEquals("/?demo-feedback=thanks", bot.headers().get("Location"), "a bot is told it worked");
        RouteResponse blank = ep.handle(post("/demo/feedback", Map.of("message", List.of("   "),
                "return", List.of("//evil.example/x")), "198.51.100.2"));
        assertEquals("/?demo-feedback=invalid", blank.headers().get("Location"), "never an off-site redirect");
        assertEquals(before, notes.count("acme"), "neither was stored");
    }

    @Test
    void aSiteWithACaptchaKeepsNoMessageWithoutTheSolvedChallenge() {
        DemoEndpoints ep = endpoints(settings(Map.of()), new AtomicLong(), captcha(true, "solved"));
        int before = notes.count("acme");
        assertEquals("/demo?demo-feedback=captcha", ep.handle(post("/demo/feedback", Map.of("message",
                List.of("hi"), "return", List.of("/demo")), "198.51.100.4")).headers().get("Location"));
        assertEquals("/?demo-feedback=captcha", ep.handle(post("/demo/feedback", Map.of("message", List.of("hi"),
                "g-recaptcha-response", List.of("wrong")), "198.51.100.4")).headers().get("Location"));
        assertEquals(before, notes.count("acme"), "an unsolved challenge keeps nothing");
        assertEquals("/?demo-feedback=thanks", ep.handle(post("/demo/feedback", Map.of("message", List.of("hi"),
                "g-recaptcha-response", List.of("solved")), "198.51.100.4")).headers().get("Location"));
        assertEquals(before + 1, notes.count("acme"));
    }

    @Test
    void feedbackReadsTheBodyOnlyNeverTheQueryString() {
        DemoEndpoints ep = endpoints(settings(Map.of()), new AtomicLong());
        RouteRequest viaQuery = new RouteRequest(SITE, Locale.ENGLISH, "POST", "/demo/feedback",
                Map.of("message", List.of("in the URL")), Map.of(), null, Map.of(), "198.51.100.3", "");
        assertEquals("/?demo-feedback=invalid", ep.handle(viaQuery).headers().get("Location"));
    }

    @Test
    void feedbackIsRateLimitedPerClientAddressWithASlidingWindow() {
        AtomicLong clock = new AtomicLong(1_000_000);
        DemoEndpoints ep = endpoints(settings(Map.of()), clock);
        Map<String, List<String>> form = Map.of("message", List.of("hi"));
        for (int i = 0; i < DemoEndpoints.FEEDBACK_PER_WINDOW; i++) {
            assertEquals("/?demo-feedback=thanks", ep.handle(post("/demo/feedback", form, "192.0.2.7"))
                    .headers().get("Location"));
        }
        assertEquals("/?demo-feedback=limited", ep.handle(post("/demo/feedback", form, "192.0.2.7"))
                .headers().get("Location"), "the sixth in the window is refused");
        assertEquals("/?demo-feedback=thanks", ep.handle(post("/demo/feedback", form, "192.0.2.8"))
                .headers().get("Location"), "another address has its own budget");
        clock.addAndGet(DemoEndpoints.WINDOW_MILLIS);
        assertEquals("/?demo-feedback=thanks", ep.handle(post("/demo/feedback", form, "192.0.2.7"))
                .headers().get("Location"), "the window slides");
    }

    @Test
    void feedbackIsNotFoundWhenTheSiteTurnedItOff() {
        RouteResponse r = endpoints(settings(Map.of("feedback.enabled", false)), new AtomicLong())
                .handle(post("/demo/feedback", Map.of("message", List.of("hi")), "192.0.2.9"));
        assertEquals(404, r.status());
    }

    @Test
    void safeLocalPathRefusesEverythingButALocalPath() {
        assertEquals("/a/b?c=1", DemoEndpoints.safeLocalPath("/a/b?c=1"));
        for (String bad : new String[] {null, "", "a", "//x", "/x://y", "/a b", "/a\\b", "/a\nb", "https://x"}) {
            assertEquals("/", DemoEndpoints.safeLocalPath(bad), String.valueOf(bad));
        }
    }
}
