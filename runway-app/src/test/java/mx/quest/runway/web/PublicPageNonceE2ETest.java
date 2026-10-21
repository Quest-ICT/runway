package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The public-page nonce, end to end: a REAL Jetty, the full egress chain (compression → CSP → head injection) and a
 * real tenant
 * page rendered by Thymeleaf — the only layer that proves the header nonce equals the body nonce across the
 * {@code PageController} → {@code HeadInjectionFilter} → {@code CspFilter} boundary, for scripts from every source:
 * the tenant's master and page, a plugin-style {@link ContentWidget}, and a {@link HeadContributor}. Then the
 * caching contract: a {@code 304} for the same ETag carries the same nonce the {@code 200}'s
 * body carries, the page stays shared-cacheable, and a response with no page ETag (a 404) stays on the allowlist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicPageNonceE2ETest {

    private static Path external;

    /** What the {@code copier} widget renders — changed by a test without moving the page's ETag. */
    private static volatile String copierOutput = "<p>clean</p>";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-public-nonce-e2e");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE, false, null));
        Path t = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(t);
        write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title>"
                + "<script src=\"/__assets/master.js\"></script>"
                + "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\"}</script>"
                + "</head><body><main id=\"content\">m</main></body></html>");
        write(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        for (String slug : new String[] {"home", "about"}) {
            write(t.resolve(slug + ".html"), "<!DOCTYPE html><html><head><title>P</title></head><body>"
                    + "<main id=\"content\"><h1>" + slug + "</h1>"
                    + "<p>" + "Big enough to clear the compression threshold. ".repeat(40) + "</p>"
                    + "<SCRIPT defer type='module' src=\"/__assets/page.js\"></SCRIPT>"
                    + "<div id=\"w\" data-widget=\"scripted/main\">PH</div></main></body></html>");
            write(t.resolve(slug + ".th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                    + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
            write(t.resolve(slug + ".meta.yml"), "path: " + ("home".equals(slug) ? "/" : "/about")
                    + "\nlocales: [en]\n");
        }
        // A review probe: markup injected into a page — an inline script and a script
        // on a host nobody allowlisted — next to an allowlisted loader (reCAPTCHA-shaped) and a same-origin script.
        write(t.resolve("probe.html"), "<!DOCTYPE html><html><head><title>P</title></head><body>"
                + "<main id=\"content\"><h1>Results for: <script>alert(document.cookie)</script></h1>"
                + "<script src=\"https://evil.example/x.js\"></script>"
                + "<script src=\"https://www.google.com/recaptcha/api.js?render=k\" async defer></script>"
                + "<script src=\"/__assets/page.js\"></script>"
                + "<script type=\"application/ld+json\">{}</script></main></body></html>");
        write(t.resolve("probe.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
        write(t.resolve("probe.meta.yml"), "path: /probe\nlocales: [en]\n");
        // Verification probes (each proven in Chrome 155), next
        // to the plugin scripts that must keep running: a carousel plugin's carousel.js, demo's analytics head script,
        // the reCAPTCHA loader + captcha-v3.js, and a same-origin script after an empty comment.
        write(t.resolve("verify.html"), "<!DOCTYPE html><html><head><title>P</title></head><body><main id=\"content\">"
                // href / xlink:href on an HTML <script> — the browser runs the TEXT
                + "<script href=\"/\">document.documentElement.setAttribute('data-p1','ran')</script>"
                + "<script xlink:href=\"/x.js\">document.documentElement.setAttribute('data-p2','ran')</script>"
                + "<script href=\"#\">document.documentElement.setAttribute('data-p3','ran')</script>"
                + "<script type=\"module\" href=\"/\">document.documentElement.setAttribute('data-p4','ran')</script>"
                + "<script type=\"importmap\" href=\"/\">{\"imports\":{\"zzz\":\"/x.js\"}}</script>"
                + "<script\nhref\n=\n/\n>document.documentElement.setAttribute('data-p6','ran')</script>"
                // The same inside SVG/MathML integration points, and SVG's src-ignored text
                + "<svg><foreignObject><script href=\"/\">r3()</script></foreignObject></svg>"
                + "<svg><desc><script href=\"/\">r4()</script></desc></svg>"
                + "<math><mtext><script href=\"/\">r5()</script></mtext></math>"
                + "<svg><script src=\"/__assets/page.js\">s1()</script></svg>"
                // Scheme-relative with extra slashes — a foreign host to the browser
                + "<script src=\"///evil.example:18191/x.js\" id=\"q1\"></script>"
                + "<script src=\"////evil.example:18191/x.js?q2\" id=\"q2\"></script>"
                + "<svg><script href=\"///evil.example:18191/x.js?r7\"></script></svg>"
                // must keep running
                // (Thymeleaf's parser needs a later "-->" to accept "<!-->"; the browser closes the comment at once)
                + "<!--><script src=\"/__assets/after-comment.js\"></script><!-- end -->"
                + "<div class=\"rw-carousel\"></div><script src=\"/__plugin/example-carousel/static/carousel.js\" defer>"
                + "</script>"
                + "<script data-demo-analytics=\"1\" src=\"/__plugin/runway-demo/static/js/demo-timing.js\""
                + " data-site=\"acme\" defer></script>"
                + "<input type=\"hidden\" class=\"rw-captcha-v3\" name=\"g-recaptcha-response\" data-sitekey=\"k\">"
                + "<script src=\"https://www.google.com/recaptcha/api.js?render=k\" async defer></script>"
                + "<script src=\"/__assets/captcha-v3.js\" async defer></script>"
                + "</main></body></html>");
        write(t.resolve("verify.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
        write(t.resolve("verify.meta.yml"), "path: /verify\nlocales: [en]\n");
        // Script-class preloads next to a module script — foreign ones (written several ways), a same-origin
        // one and one on an allowlisted host. The master supplies <head>, so the links sit in the body (body-ok).
        write(t.resolve("preloads.html"), "<!DOCTYPE html><html><head><title>P</title></head><body>"
                + "<main id=\"content\"><h1>preloads</h1>"
                + "<link rel=\"modulepreload\" href=\"https://evil.example:18191/m.js\">"
                + "<LINK REL='nofollow ModulePreload' HREF='///evil.example:18191/m2.js'>"
                + "<link rel=\"prefetch\" as=\"script\" href=\"//evil.example:18191/p\">"
                + "<link rel=\"preload\" as=\"script\" href=\"https&#58;//evil.example:18191/c.js\">"
                + "<link rel=\"modulepreload\" href=\"/__assets/m.js\">"
                + "<link rel=\"preload\" as=\"script\" href=\"https://www.google.com/recaptcha/api.js\">"
                + "<link rel=\"preconnect\" href=\"https://fonts.example\">"
                + "<script type=\"module\" src=\"/__assets/m.js\"></script></main></body></html>");
        write(t.resolve("preloads.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
        write(t.resolve("preloads.meta.yml"), "path: /preloads\nlocales: [en]\n");
        // A page whose widget output the ETag does not fold (like per-widget instance config): the srcdoc-copy scenario.
        write(t.resolve("copied.html"), "<!DOCTYPE html><html><head><title>C</title></head><body><main id=\"content\">"
                + "<script src=\"/__assets/page.js\"></script>"
                + "<div id=\"w\" data-widget=\"copier/main\">PH</div></main></body></html>");
        write(t.resolve("copied.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
        write(t.resolve("copied.meta.yml"), "path: /copied\nlocales: [en]\n");
        // A page whose master does not exist: rendering throws AFTER the nonce was published → the 500 page.
        write(t.resolve("broken.html"), "<!DOCTYPE html><html><head><title>B</title></head><body>b</body></html>");
        write(t.resolve("broken.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");
        write(t.resolve("broken.meta.yml"), "path: /broken\nlocales: [en]\ntemplate: _absent\n");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Nonce-E2E-Secret-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.csp.mode", () -> "enforce");
        // The hosts the allowlist policy names: the head contributor's analytics host and the reCAPTCHA host.
        registry.add("runway.csp.extra-hosts[0]", () -> "https://analytics.example");
        registry.add("runway.csp.extra-hosts[1]", () -> "https://www.google.com");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A plugin-style widget and head contributor that emit scripts the kernel knows nothing about. */
    @TestConfiguration
    static class ScriptEmitters {
        @Bean
        ContentWidget scriptedWidget() {
            return new ContentWidget() {
                @Override public String key() { return "scripted"; }
                @Override public RenderFragment render(WidgetView view) {
                    return RenderFragment.of("<script src=\"/__assets/widget.js\"></script><p>widget</p>");
                }
            };
        }

        /** Renders {@link #copierOutput}: markup an input the ETag does not fold could carry. */
        @Bean
        ContentWidget copierWidget() {
            return new ContentWidget() {
                @Override public String key() { return "copier"; }
                @Override public RenderFragment render(WidgetView view) {
                    return RenderFragment.of(copierOutput);
                }
            };
        }

        @Bean
        HeadContributor scriptedHead() {
            return new HeadContributor() {
                @Override public String key() { return "scripted-head"; }
                @Override public String render(HeadContext context) {
                    return "<script async src=\"https://analytics.example/a.js\"></script>";
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    private static final Pattern NONCE = Pattern.compile("'nonce-([A-Za-z0-9_-]{16,})'");
    private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final String CSP = "Content-Security-Policy";

    private static String nonceOf(String csp) {
        Matcher m = NONCE.matcher(csp == null ? "" : csp);
        return m.find() ? m.group(1) : null;
    }

    private HttpResponse<String> get(String path, String ifNoneMatch) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (ifNoneMatch != null) {
            req.header("If-None-Match", ifNoneMatch);
        }
        return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> scriptTags(String body) {
        List<String> tags = new ArrayList<>();
        Matcher m = SCRIPT_TAG.matcher(body);
        while (m.find()) {
            tags.add(m.group());
        }
        return tags;
    }

    @Test
    void theHeaderNonceIsOnEveryExecutableScriptFromEverySourceAndA304CarriesTheSameOne() throws Exception {
        HttpResponse<String> page = get("/", null);
        assertEquals(200, page.statusCode(), page.body());

        String csp = page.headers().firstValue(CSP).orElse(null);
        String nonce = nonceOf(csp);
        assertNotNull(nonce, "a public page carries the derived nonce: " + csp);
        assertTrue(csp.contains("'nonce-" + nonce + "' 'strict-dynamic' 'self'"),
                "strict-dynamic with the 'self' fallback retained: " + csp);

        List<String> tags = scriptTags(page.body());
        assertEquals(5, tags.size(), "master + ld+json + page + widget + head contributor: " + tags);
        for (String tag : tags) {
            if (tag.contains("application/ld+json")) {
                assertFalse(tag.contains("nonce"), "a JSON-LD data block is never stamped: " + tag);
            } else {
                assertTrue(tag.contains("nonce=\"" + nonce + "\""), "header nonce on " + tag);
            }
        }
        assertTrue(page.body().contains("src=\"/__assets/widget.js\""), "the widget rendered");
        assertTrue(page.body().contains("src=\"https://analytics.example/a.js\""), "the head contributor ran");

        String etag = page.headers().firstValue("ETag").orElse(null);
        assertNotNull(etag);
        String cacheControl = page.headers().firstValue("Cache-Control").orElse("");
        assertFalse(cacheControl.contains("no-store"), "a version-stable nonce keeps the page cacheable: " + cacheControl);

        // Revalidation: the 304 must carry the header whose nonce matches the body the client already holds.
        HttpResponse<String> notModified = get("/", etag);
        assertEquals(304, notModified.statusCode());
        assertEquals(nonce, nonceOf(notModified.headers().firstValue(CSP).orElse(null)),
                "304 header nonce == the 200 body's nonce");
        assertEquals(etag, notModified.headers().firstValue("ETag").orElse(null));

        // Stable per content version: a fresh GET of the unchanged page derives the same nonce.
        assertEquals(nonce, nonceOf(get("/", null).headers().firstValue(CSP).orElse(null)));
    }

    @Test
    void anotherPageVersionGetsAnotherNonce() throws Exception {
        String home = nonceOf(get("/", null).headers().firstValue(CSP).orElse(null));
        HttpResponse<String> about = get("/about", null);
        assertEquals(200, about.statusCode());
        String aboutNonce = nonceOf(about.headers().firstValue(CSP).orElse(null));
        assertNotNull(aboutNonce);
        assertNotEquals(home, aboutNonce, "different page, different ETag, different nonce");
        for (String tag : scriptTags(about.body())) {
            if (!tag.contains("ld+json")) {
                assertTrue(tag.contains("nonce=\"" + aboutNonce + "\""), tag);
            }
        }
    }

    /**
     * Pre-flight D, end to end: only what the host-allowlist policy would have run carries the nonce. The injected
     * inline script and the foreign-host script stay unstamped — under {@code 'strict-dynamic'} a browser blocks
     * both, exactly as the allowlist policy did — while the allowlisted loader and the same-origin script are
     * stamped (the loader's own dynamically inserted scripts are then trusted through {@code 'strict-dynamic'}).
     * A {@code 304} carries the same header as the {@code 200}.
     */
    @Test
    void injectedAndForeignScriptsAreNotStampedOnlyAllowlistedOnesAre() throws Exception {
        HttpResponse<String> page = get("/probe", null);
        assertEquals(200, page.statusCode(), page.body());
        String csp = page.headers().firstValue(CSP).orElse(null);
        String nonce = nonceOf(csp);
        assertNotNull(nonce, csp);
        assertTrue(csp.contains("'nonce-" + nonce + "' 'strict-dynamic' 'self' https://analytics.example "
                + "https://www.google.com"), csp);

        String body = page.body();
        assertTrue(body.contains("<script>alert(document.cookie)</script>"), "injected inline left as is: " + body);
        assertTrue(body.contains("<script src=\"https://evil.example/x.js\">"), "foreign host left as is: " + body);
        assertTrue(body.contains("<script nonce=\"" + nonce + "\" src=\"https://www.google.com/recaptcha/api.js"
                + "?render=k\""), "allowlisted loader stamped: " + body);
        assertTrue(body.contains("<script nonce=\"" + nonce + "\" src=\"/__assets/page.js\">"), body);
        assertTrue(body.contains("<script type=\"application/ld+json\">{}</script>"), "data block untouched: " + body);
        long stamped = scriptTags(body).stream().filter(tag -> tag.contains("nonce=")).count();
        assertEquals(4, stamped, "master + head contributor + loader + same-origin page script: " + scriptTags(body));

        HttpResponse<String> notModified = get("/probe", page.headers().firstValue("ETag").orElseThrow());
        assertEquals(304, notModified.statusCode());
        assertEquals(csp, notModified.headers().firstValue(CSP).orElse(null), "the 304 carries the 200's policy");
    }

    /**
     * The verification probes through the real chain: no href-only script (href / xlink:href, no src),
     * no SVG script with text, no triple-slash script is stamped; the plugin scripts that must keep working are
     * (carousel.js, demo analytics, the reCAPTCHA loader + captcha-v3.js), and so is a script after {@code <!-->}.
     */
    @Test
    void theVerificationProbesStayBlockedAndThePluginScriptsStillRun() throws Exception {
        HttpResponse<String> page = get("/verify", null);
        assertEquals(200, page.statusCode(), page.body());
        String nonce = nonceOf(page.headers().firstValue(CSP).orElse(null));
        assertNotNull(nonce);
        List<String> stamped = new ArrayList<>();
        for (String tag : scriptTags(page.body())) {
            if (tag.contains("nonce=")) {
                assertTrue(tag.startsWith("<script nonce=\"" + nonce + "\""), tag);
                stamped.add(tag);
            }
        }
        List<String> expected = List.of("/__assets/master.js", "https://analytics.example/a.js",
                "/__assets/after-comment.js", "/__plugin/example-carousel/static/carousel.js",
                "/__plugin/runway-demo/static/js/demo-timing.js", "https://www.google.com/recaptcha/api.js?render=k",
                "/__assets/captcha-v3.js");
        assertEquals(expected.size(), stamped.size(), "exactly the allowed scripts: " + stamped);
        for (String src : expected) {
            assertTrue(stamped.stream().anyMatch(tag -> tag.contains("src=\"" + src + "\"")), src + " in " + stamped);
        }
        String body = page.body();
        assertTrue(body.contains("<script href=\"/\">document"), "href-only script left as rendered: " + body);
        assertTrue(body.contains("<svg><script src=\"/__assets/page.js\">s1()</script></svg>"), body);
        assertTrue(body.contains("<script src=\"///evil.example:18191/x.js\" id=\"q1\"></script>"), body);
    }

    /**
     * End to end: markup from an input the ETag does not fold copies the published
     * nonce into an {@code <iframe srcdoc>} (which inherits the page's policy). The page's ETag does not move, so the
     * copy matches — and the response therefore gets no nonce at all: the host-allowlist policy, no stamp, no
     * validators, no-store. A revalidation with the old validator still answers 304 with the nonced header, which
     * pairs only with the clean body the client already has.
     */
    @Test
    void aCopiedNonceWithdrawsTheNonceForThatResponse() throws Exception {
        copierOutput = "<p>clean</p>";
        HttpResponse<String> clean = get("/copied", null);
        assertEquals(200, clean.statusCode(), clean.body());
        String csp = clean.headers().firstValue(CSP).orElseThrow();
        String nonce = nonceOf(csp);
        String etag = clean.headers().firstValue("ETag").orElseThrow();
        assertNotNull(nonce, csp);
        try {
            copierOutput = "<iframe srcdoc=\"&lt;script nonce=&quot;" + nonce
                    + "&quot;&gt;parent.document.documentElement.setAttribute('data-p15','ran')&lt;/script&gt;\">"
                    + "</iframe>";
            HttpResponse<String> copied = get("/copied", null);
            assertEquals(200, copied.statusCode(), copied.body());
            String fallback = copied.headers().firstValue(CSP).orElseThrow();
            assertNull(nonceOf(fallback), "no nonce in the header: " + fallback);
            assertFalse(fallback.contains("'strict-dynamic'"), fallback);
            assertTrue(fallback.contains("script-src 'self' https://analytics.example https://www.google.com"),
                    fallback);
            assertFalse(copied.body().contains("<script nonce="), "nothing stamped: " + copied.body());
            assertTrue(copied.body().contains("&lt;script nonce=&quot;" + nonce), "the copy is inert: " + copied.body());
            assertTrue(copied.headers().firstValue("ETag").isEmpty(), "no validator");
            assertEquals("no-store", copied.headers().firstValue("Cache-Control").orElse(null));

            HttpResponse<String> revalidated = get("/copied", etag);
            assertEquals(304, revalidated.statusCode());
            assertEquals(csp, revalidated.headers().firstValue(CSP).orElse(null));
        } finally {
            copierOutput = "<p>clean</p>";
        }
    }

    /**
     * End to end: under the nonce + {@code 'strict-dynamic'} every script-class preload on a host the
     * allowlist policy refused is gone from the body — however it was written — while the same-origin preload, the
     * allowlisted one and a non-script link stay as rendered and the module script is stamped. The page keeps its
     * ETag and cacheability (the removal is part of the one body that ETag describes), and a 304 carries the 200's
     * policy.
     */
    @Test
    void foreignScriptPreloadsAreRemovedAndAllowedOnesStay() throws Exception {
        HttpResponse<String> page = get("/preloads", null);
        assertEquals(200, page.statusCode(), page.body());
        String csp = page.headers().firstValue(CSP).orElseThrow();
        String nonce = nonceOf(csp);
        assertNotNull(nonce, csp);
        String body = page.body();
        assertFalse(body.contains("evil.example"), "every foreign script-class preload removed: " + body);
        assertTrue(body.contains("<link rel=\"modulepreload\" href=\"/__assets/m.js\">"), body);
        assertTrue(body.contains("<link rel=\"preload\" as=\"script\" href=\"https://www.google.com/recaptcha/api.js\">"),
                "an allowlisted host stays: " + body);
        assertTrue(body.contains("<link rel=\"preconnect\" href=\"https://fonts.example\">"),
                "not a script-class link: " + body);
        assertTrue(body.contains("<script nonce=\"" + nonce + "\" type=\"module\" src=\"/__assets/m.js\">"), body);

        String etag = page.headers().firstValue("ETag").orElseThrow();
        assertFalse(page.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(body, get("/preloads", null).body(), "deterministic: one ETag, one body");
        HttpResponse<String> notModified = get("/preloads", etag);
        assertEquals(304, notModified.statusCode());
        assertEquals(csp, notModified.headers().firstValue(CSP).orElse(null));
    }

    /**
     * A foreign preload where the edge cannot remove it — inside an SVG {@code <title>}, real markup to the
     * browser — arrives through an input the ETag does not fold; the response then gets no nonce (the host-allowlist
     * policy, whose {@code 'self'} blocks the fetch), no stamp, no validators and no-store.
     */
    @Test
    void aForeignPreloadTheEdgeCannotRemoveWithdrawsTheNonce() throws Exception {
        copierOutput = "<p>clean</p>";
        try {
            copierOutput = "<svg><title><link rel=\"modulepreload\" href=\"https://evil.example/m.js\"></title></svg>";
            HttpResponse<String> hidden = get("/copied", null);
            assertEquals(200, hidden.statusCode(), hidden.body());
            String csp = hidden.headers().firstValue(CSP).orElseThrow();
            assertNull(nonceOf(csp), "no nonce in the header: " + csp);
            assertFalse(csp.contains("'strict-dynamic'"), csp);
            assertTrue(csp.contains("script-src 'self' https://analytics.example https://www.google.com"), csp);
            assertFalse(hidden.body().contains("nonce="), "nothing stamped: " + hidden.body());
            assertTrue(hidden.body().contains(copierOutput), "the body is not rewritten: " + hidden.body());
            assertTrue(hidden.headers().firstValue("ETag").isEmpty(), "no validator");
            assertEquals("no-store", hidden.headers().firstValue("Cache-Control").orElse(null));
        } finally {
            copierOutput = "<p>clean</p>";
        }
    }

    /** No page ETag ⇒ no derived nonce: the 404 page keeps the host-allowlist policy and its body is unstamped. */
    @Test
    void anErrorPageStaysOnTheAllowlistPolicy() throws Exception {
        HttpResponse<String> missing = get("/no-such-page", null);
        assertEquals(404, missing.statusCode());
        String csp = missing.headers().firstValue(CSP).orElse(null);
        assertNotNull(csp, "the error page is still governed by CSP");
        assertNull(nonceOf(csp), "no nonce on a response without a page ETag: " + csp);
        assertFalse(csp.contains("'strict-dynamic'"), csp);
        assertFalse(missing.body().contains("nonce="), "nothing stamped into the error body");
    }

    /** A render that fails after the nonce was published serves the 500 page WITHOUT that page's nonce. */
    @Test
    void aFailedRenderWithdrawsThePublishedNonce() throws Exception {
        HttpResponse<String> broken = get("/broken", null);
        assertEquals(500, broken.statusCode(), broken.body());
        String csp = broken.headers().firstValue(CSP).orElse(null);
        assertNotNull(csp);
        assertNull(nonceOf(csp), "the error page must not inherit the failed page's nonce: " + csp);
        assertFalse(csp.contains("'strict-dynamic'"), csp);
    }

    /** With compression buffering outermost (client sent Accept-Encoding) header and body still agree. */
    @Test
    void headerAndBodyAgreeUnderCompression() throws Exception {
        HttpResponse<byte[]> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).header("Accept-Encoding", "gzip")
                        .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode());
        assertEquals("gzip", res.headers().firstValue("Content-Encoding").orElse(null),
                "precondition: the compression filter really buffered and encoded this response");
        String body = new String(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(res.body()))
                .readAllBytes(), StandardCharsets.UTF_8);
        String nonce = nonceOf(res.headers().firstValue(CSP).orElse(null));
        assertNotNull(nonce);
        long stamped = scriptTags(body).stream().filter(tag -> tag.contains("nonce=\"" + nonce + "\"")).count();
        assertEquals(4, stamped, "every executable script carries the header nonce: " + body);
    }

    @Test
    void theSecretWasCreatedOwnerOnlyUnderExternal() throws Exception {
        get("/", null);
        Path key = external.resolve(PublicPageNonce.KEY_FILE);
        assertTrue(Files.exists(key), "created on first need");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key)));
    }
}
