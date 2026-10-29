package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.authz.Role;
import mx.quest.runway.authz.RoleFile;
import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.console.AssetCachePolicy;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.log.LogKind;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.LoginFormGuard;
import mx.quest.runway.security.LoginFormVerdict;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An encoded spelling of a path is classified like the plain path, by every filter, interceptor and listener that
 * classifies requests by path (RequestPaths). Against a real Jetty and the real filter chain, over a raw socket so
 * each request names its {@code Host} and its exact path bytes.
 *
 * <p>Jetty and Spring Security's firewall already refuse {@code %2F}, {@code %2E}, {@code %25}, {@code ;params} and
 * dot segments with {@code 400} (see {@link RequestPaths}); what reaches the handlers is any other percent-encoded
 * character, and Spring routes it decoded. So each test sends {@code %5F} for {@code _}, {@code %6C} for {@code l}
 * and the like, and checks the request is treated as the handler that serves it.
 *
 * <p>Sites: {@code main} (default, {@code main.test}), {@code shop}, a suspended {@code sus}, a {@code mnt} in
 * maintenance, a quarantined {@code quar} (invalid {@code roles.yml}) and {@code quota} (a one-byte bandwidth cap).
 * {@code runway.console.hosts} lists all of their hosts, so the app realm's exemptions can be seen on each.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EncodedPathEndToEndTest {

    private static final String PASSWORD = "Console-Host-9";
    private static final String SCRAPE_TOKEN = "encoded-path-scrape-token-1";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*?value=\"([^\"]*)\"");

    /** The test gate locks every client while set; the test guard rejects every form while set. */
    static final AtomicBoolean LOCKED = new AtomicBoolean();
    static final AtomicBoolean REJECT = new AtomicBoolean();

    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-encoded-path");
        seed(external);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.console.hosts", () -> "main.test,sus.test,mnt.test,quar.test,quota.test");
        registry.add("runway.bandwidth.quota.per-site.quota", () -> "1");
        registry.add("runway.metrics.scrape-token", () -> SCRAPE_TOKEN);
    }

    static void seed(Path external) throws IOException {
        ConsoleHostEndToEndTest.seed(external, "main");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(site("sus", SiteStatus.SUSPENDED, false));
        sites.save(site("mnt", SiteStatus.ACTIVE, true));
        sites.save(site("quar", SiteStatus.ACTIVE, false));
        sites.save(site("quota", SiteStatus.ACTIVE, false));
        // An invalid role graph (a role inheriting a role that does not exist) quarantines quar's admin surface.
        yaml.write(layout.siteDir("quar").resolve("roles.yml"),
                new RoleFile(List.of(new Role("a", Realm.TENANT, Set.of(), Set.of("ghost")))));
    }

    private static Site site(String id, SiteStatus status, boolean maintenance) {
        return new Site(id, id, List.of(id + ".test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null, status,
                maintenance, maintenance ? "Back soon" : null);
    }

    @TestConfiguration
    static class Beans {

        @Bean
        @Primary
        LoginAttemptGate switchableGate() {
            return new LoginAttemptGate() {
                @Override
                public boolean isLocked(String clientIp) {
                    return LOCKED.get();
                }

                @Override
                public void recordFailure(String clientIp) {
                }

                @Override
                public void recordSuccess(String clientIp) {
                }
            };
        }

        @Bean
        @Primary
        LoginFormGuard switchableGuard() {
            return (request, scope) -> REJECT.get()
                    ? LoginFormVerdict.reject(LoginFormVerdict.Reason.CAPTCHA) : LoginFormVerdict.ok();
        }

        /** Shows what {@link RequestContext#site()} answers for each request that gets past security. */
        @Bean
        FilterRegistrationBean<Filter> requestContextProbe(RequestContext context) {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((req, res, chain) -> {
                ((HttpServletResponse) res).setHeader("X-Test-Site",
                        context.site().map(site -> site.id()).orElse("none"));
                chain.doFilter(req, res);
            });
            registration.setOrder(FilterOrder.CORE_SECURITY + 10);
            return registration;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private BandwidthService bandwidth;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private AppActivityLog appActivity;

    @Autowired
    private SiteLogService siteLogs;

    @AfterEach
    void reset() {
        LOCKED.set(false);
        REJECT.set(false);
    }

    private RawHttp.Response get(String host, String path) throws IOException {
        return get(host, path, Map.of());
    }

    private RawHttp.Response get(String host, String path, Map<String, String> headers) throws IOException {
        return RawHttp.send(port, "GET", host + ":" + port, path, null, headers);
    }

    /** A session (its cookies) and the form token of the page {@code page} on {@code host}. */
    private record Session(String cookie, String csrf) {
        Map<String, String> headers() {
            return Map.of("Cookie", cookie);
        }
    }

    private Session open(String host, String page) throws IOException {
        RawHttp.Response res = get(host, page);
        assertEquals(200, res.status(), page + " on " + host);
        Matcher token = CSRF.matcher(res.body());
        assertTrue(token.find(), "a form token on " + page);
        return new Session(String.join("; ", res.cookies()), token.group(1));
    }

    private RawHttp.Response post(String host, String path, Session session, String form) throws IOException {
        String body = form + "&_csrf=" + URLEncoder.encode(session.csrf(), StandardCharsets.UTF_8);
        return RawHttp.send(port, "POST", host + ":" + port, path, body, session.headers());
    }

    private static String location(RawHttp.Response res) {
        String location = res.header("location");
        assertNotNull(location, "a redirect, got " + res.status());
        return location.replaceFirst("^https?://[^/]+", "");
    }

    private static String csp(RawHttp.Response res) {
        String header = res.header("content-security-policy");
        return header != null ? header : res.header("content-security-policy-report-only");
    }

    // --- Sign-in protection (LoginProtectionFilter) --------------------------------------------------------------

    @Test
    void aLockedOutClientIsLockedOutOnEveryEncodedSpellingOfTheSignIn() throws IOException {
        LOCKED.set(true);
        for (String path : List.of("/app/login", "/app/%6Cogin", "/%61pp/login", "/app/logi%6E")) {
            Session session = open("main.test", "/app/login");
            RawHttp.Response res = post("main.test", path, session, "username=root&password=" + PASSWORD);
            assertEquals(302, res.status(), path);
            assertEquals("/app/login?locked", location(res), "POST " + path);
        }
        for (String path : List.of("/login", "/log%69n", "/%6Cogin")) {
            Session session = open("main.test", "/login");
            RawHttp.Response res = post("main.test", path, session, "username=root&password=" + PASSWORD);
            assertEquals("/login?locked", location(res), "POST " + path);
        }
    }

    @Test
    void theFormGuardRunsOnEveryEncodedSpellingOfSignInAndRecovery() throws IOException {
        REJECT.set(true);
        for (String path : List.of("/app/login", "/app/%6Cogin")) {
            Session session = open("main.test", "/app/login");
            RawHttp.Response res = post("main.test", path, session, "username=root&password=" + PASSWORD);
            assertEquals("/app/login?captcha", location(res), "POST " + path);
        }
        for (String path : List.of("/app/forgot-password", "/app/forgot-p%61ssword")) {
            Session session = open("main.test", "/app/forgot-password");
            RawHttp.Response res = post("main.test", path, session, "email=a%40b.test");
            assertEquals("/app/forgot-password?captcha", location(res), "POST " + path);
        }
        for (String path : List.of("/forgot-password", "/forgot-p%61ssword")) {
            Session session = open("main.test", "/forgot-password");
            RawHttp.Response res = post("main.test", path, session, "email=a%40b.test");
            assertEquals("/forgot-password?captcha", location(res), "POST " + path);
        }
    }

    // --- Audit (AppAuditEventListener, AuditEventListener) -------------------------------------------------------

    @Test
    void anEncodedFailedSignInIsAuditedByTheConsoleNotByTheSite() throws IOException {
        String operator = "enc-audit-" + System.nanoTime();
        Session session = open("main.test", "/app/login");
        RawHttp.Response failed = post("main.test", "/app/%6Cogin", session, "username=" + operator + "&password=x");
        assertEquals("/app/login?error", location(failed));
        assertTrue(appActivity.tail(500).stream().anyMatch(l -> l.contains(operator) && l.contains("login.failure")),
                "the operator audit records the failed console sign-in");

        // A failed sign-in on the site's own login lands in its audit log; once it is there, the earlier one would be.
        String control = "site-audit-" + System.nanoTime();
        Session site = open("main.test", "/login");
        assertEquals("/login?error", location(post("main.test", "/login", site, "username=" + control + "&password=x")));
        awaitLine("main", LogKind.AUDIT, control);
        assertFalse(siteLogs.tail("main", LogKind.AUDIT, 500).stream().anyMatch(l -> l.contains(operator)),
                "a console sign-in is not written into the site's audit log");
    }

    // --- The console page itself: CSP, no-store, topbar, language (CspFilter, SecurityHeaders, shell, i18n) ------

    @Test
    void anEncodedConsolePageIsServedAsTheConsole() throws IOException {
        Session login = open("main.test", "/app/login");
        RawHttp.Response signedIn = post("main.test", "/app/login", login, "username=root&password=" + PASSWORD);
        assertEquals("/__app", location(signedIn), "signed in");
        Map<String, String> cookie = Map.of("Cookie", String.join("; ", signedIn.cookies()));
        for (String path : List.of("/__app/sites", "/%5F%5Fapp/sites", "/%5f_app/sites", "/__ap%70/sites")) {
            RawHttp.Response page = get("main.test", path, cookie);
            assertEquals(200, page.status(), path);
            assertTrue(csp(page).contains("'strict-dynamic'") && csp(page).contains("'nonce-"), path + ": " + csp(page));
            assertTrue(page.header("cache-control").contains("no-store"), path + ": " + page.header("cache-control"));
            assertTrue(page.body().contains("rw-topbar-app"), path + ": the application console's topbar");
            assertFalse(page.body().contains("rw-topbar-admin"), path);
        }
    }

    @Test
    void anEncodedConsoleRedirectIsNotStorableEither() throws IOException {
        for (String path : List.of("/__app/users", "/%5F%5Fapp/users", "/%5F%5Fadmin/pages")) {
            RawHttp.Response res = get("main.test", path);
            assertEquals(302, res.status(), path);
            String cacheControl = res.header("cache-control");
            assertTrue(cacheControl != null && cacheControl.contains("no-store"), path + ": " + cacheControl);
        }
    }

    @Test
    void anEncodedSignInOrRecoveryPageIsTheNoncedKernelSurfaceInTheOperatorsLanguage() throws IOException {
        for (String path : List.of("/app/login", "/app/%6Cogin", "/app/forgot-p%61ssword", "/log%69n",
                "/forgot-p%61ssword")) {
            RawHttp.Response page = get("main.test", path, Map.of("Accept-Language", "de"));
            assertEquals(200, page.status(), path);
            assertTrue(csp(page).contains("'strict-dynamic'"), path + ": " + csp(page));
            // German is not a console language: the operator resolver falls back to English (a public page would
            // follow the browser), and the labels come from the console's bundle (the site's would show ??key??).
            assertTrue(page.body().contains("<html lang=\"en\""), path + ": the console's language");
            assertFalse(page.body().contains("??"), path + ": every label resolved from the console bundle");
        }
    }

    // --- RequestContext (plugins asking for "the current site") ---------------------------------------------------

    @Test
    void theAppRealmReportsNoSiteOnEverySpellingAndOnRecovery() throws IOException {
        for (String path : List.of("/app/login", "/app/%6Cogin", "/app/forgot-password", "/app/forgot-p%61ssword",
                "/app/reset")) {
            RawHttp.Response res = get("main.test", path);
            assertEquals("none", res.header("x-test-site"), path + " -> " + res.status());
        }
        assertEquals("main", get("main.test", "/login").header("x-test-site"), "a tenant page reports its site");
    }

    // --- Exemptions: suspended, maintenance, quarantine, quota -----------------------------------------------------

    @Test
    void aSuspendedSitesHostStillReachesTheConsoleOnEverySpelling() throws IOException {
        assertEquals(503, get("sus.test", "/").status());
        assertEquals(503, get("sus.test", "/login").status(), "the site's own console is suspended too");
        for (String path : List.of("/app/login", "/app/%6Cogin", "/app/forgot-password", "/app/forgot-p%61ssword",
                "/app/reset")) {
            assertEquals(200, get("sus.test", path).status(), path);
        }
        RawHttp.Response console = get("sus.test", "/%5F%5Fapp/sites");
        assertEquals(302, console.status());
        assertEquals("/app/login", location(console));
    }

    @Test
    void aSiteInMaintenanceKeepsItsConsoleAndRecoveryOnEverySpelling() throws IOException {
        assertEquals(503, get("mnt.test", "/").status());
        for (String path : List.of("/login", "/log%69n", "/forgot-password", "/forgot-p%61ssword",
                "/app/login", "/app/%6Cogin", "/app/forgot-password")) {
            assertEquals(200, get("mnt.test", path).status(), path);
        }
        assertNotEquals(503, get("mnt.test", "/reset").status(), "/reset");
        for (String path : List.of("/__admin", "/%5F%5Fadmin", "/%5F%5Fadmin/pages")) {
            RawHttp.Response res = get("mnt.test", path);
            assertEquals(302, res.status(), path);
            assertEquals("/login", location(res), path);
        }
    }

    @Test
    void aQuarantinedSitesAdminIsHeldOnEverySpelling() throws IOException {
        for (String path : List.of("/__admin", "/%5F%5Fadmin", "/%5f%5fadmin/pages", "/__a%64min/users")) {
            RawHttp.Response res = get("quar.test", path);
            assertEquals(503, res.status(), path);
            assertTrue(res.body().contains("administration is temporarily unavailable"), path);
        }
        assertEquals(200, get("quar.test", "/login").status(), "only the admin surface is held");
    }

    @Test
    void aSiteOverItsQuotaKeepsItsConsoleAndRecoveryOnEverySpelling() throws IOException {
        bandwidth.record("quota", 0, 1_000_000);
        bandwidth.refreshQuotas();
        assertEquals(503, get("quota.test", "/").status());
        for (String path : List.of("/login", "/log%69n", "/forgot-password", "/forgot-p%61ssword", "/app/%6Cogin",
                "/app/forgot-password")) {
            assertEquals(200, get("quota.test", path).status(), path);
        }
        assertEquals(302, get("quota.test", "/%5F%5Fadmin").status());
    }

    // --- Metering, metrics, access log ----------------------------------------------------------------------------

    @Test
    void encodedConsoleTrafficIsNotChargedToTheHostsSite() throws IOException {
        long bytesBefore = bytesOut("main");
        double requestsBefore = requests("main");
        for (String path : List.of("/%5F%5Fapp/sites", "/app/%6Cogin", "/app/forgot-password", "/%5F%5Fapp")) {
            assertNotEquals(503, get("main.test", path).status(), path);
        }
        // The filters record after the response is on the wire, so wait for a later public request (the control) to
        // be counted; by then the console requests above would have been counted too.
        RawHttp.Response control = get("main.test", "/no-such-page");
        assertEquals(404, control.status());
        long deadline = System.nanoTime() + 15_000_000_000L;
        while ((requests("main") < requestsBefore + 1 || bytesOut("main") < bytesBefore + control.body().getBytes(StandardCharsets.UTF_8).length)
                && System.nanoTime() < deadline) {
            pause();
        }
        assertEquals(requestsBefore + 1, requests("main"), "only the public request is counted for main");
        long charged = bytesOut("main") - bytesBefore;
        assertTrue(charged >= control.body().getBytes(StandardCharsets.UTF_8).length && charged < control.body().getBytes(StandardCharsets.UTF_8).length + 4096,
                "only the public request's bytes are charged to main: " + charged + " for a body of "
                        + control.body().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void anEncodedConsoleRequestIsNotWrittenToTheSitesAccessLog() throws IOException {
        get("main.test", "/app/%6Cogin");
        get("main.test", "/%5F%5Fapp/sites");
        String control = "/access-control-" + System.nanoTime();
        get("main.test", control);
        awaitLine("main", LogKind.ACCESS, control);
        List<String> lines = siteLogs.tail("main", LogKind.ACCESS, 1000);
        // The console's paths only: a site's own login spelled /%6Cogin is public traffic, and logged since 1.1.2.
        assertFalse(lines.stream().anyMatch(l -> l.contains("/app/%6Cogin") || l.contains("%5F%5Fapp")),
                lines.toString());
    }

    // --- Health, reload, Prometheus, console assets, the error page -------------------------------------------------

    @Test
    void theHealthProbeAnswersAnEncodedSpelling() throws IOException {
        RawHttp.Response plain = get("main.test", "/__health");
        RawHttp.Response encoded = get("main.test", "/%5F%5Fhealth");
        assertEquals(200, plain.status());
        assertEquals(200, encoded.status());
        assertEquals(plain.header("content-type"), encoded.header("content-type"));
    }

    @Test
    void theReloadEndpointAnswersAnEncodedSpelling() throws IOException {
        // Reload is off here, so the endpoint's own JSON "not found" answers, not the site's 404 page.
        for (String path : List.of("/__reload/sites", "/%5F%5Freload/sites")) {
            RawHttp.Response res = get("main.test", path);
            assertEquals(404, res.status(), path);
            assertTrue(res.body().contains("{\"error\":\"not found\"}"), path + ": " + res.body());
        }
    }

    @Test
    void theScrapeTokenWorksOnAnEncodedSpellingOfThePrometheusEndpoint() throws IOException {
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + SCRAPE_TOKEN);
        for (String path : List.of("/actuator/prometheus", "/actuator/%70rometheus")) {
            assertEquals(200, get("main.test", path, bearer).status(), path);
            assertEquals(302, get("main.test", path).status(), path + " without the token");
        }
    }

    @Test
    void anEncodedFontIsCachedLikeTheFont() throws IOException {
        for (String path : List.of("/__assets/vendor/fontawesome/webfonts/fa-solid-900.woff2",
                "/__assets/vendor/fontawesome/webfonts/fa-solid-900.wof%662")) {
            RawHttp.Response res = get("main.test", path);
            assertEquals(200, res.status(), path);
            assertEquals(AssetCachePolicy.VERSIONED, res.header("cache-control"), path);
        }
    }

    @Test
    void anErrorOnAnEncodedConsolePathShowsTheConsolesErrorPage() throws IOException {
        // A ;param is refused (400) by the firewall; the error page is chosen from the original URI.
        for (String path : List.of("/__app/sites;x=1", "/%5F%5Fapp/sites;x=1")) {
            RawHttp.Response res = get("main.test", path);
            assertEquals(400, res.status(), path);
            assertTrue(res.body().contains("Back to the console"), path + ": " + res.body());
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private long bytesOut(String siteId) {
        return bandwidth.aggregate().stream().filter(s -> s.siteId().equals(siteId))
                .mapToLong(BandwidthService.SiteBandwidth::bytesOut).sum();
    }

    private double requests(String siteId) {
        return meters.find("runway.http.server.requests").tag("site", siteId).timers().stream()
                .mapToDouble(Timer::count).sum();
    }

    /** Waits (up to 15 s) until the site's log of {@code kind} has a line containing {@code text}. */
    private void awaitLine(String siteId, LogKind kind, String text) {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (siteLogs.tail(siteId, kind, 1000).stream().anyMatch(l -> l.contains(text))) {
                return;
            }
            pause();
        }
        throw new AssertionError("no " + kind + " line with " + text + " for " + siteId);
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
