package mx.quest.demodatabase;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import mx.quest.database.spi.DatabaseConsumer;
import mx.quest.database.spi.Databases;
import mx.quest.database.spi.Dialect;
import mx.quest.database.spi.PluginDatabase;
import mx.quest.database.spi.Scope;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.Test;

class GuestbookEndpointsTest {

    /** A site's CAPTCHA: inactive, or active and accepting one token only. */
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

    static final SiteView SITE = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);

    /** A database that is not available and fails the test if anything tries to use a scope. */
    static Databases unavailable() {
        PluginDatabase db = new PluginDatabase() {
            public boolean available() {
                return false;
            }

            public Dialect dialect() {
                throw new AssertionError("no dialect while unavailable");
            }

            public String prefix() {
                return "runway_demo_database__";
            }

            public Scope currentSite() {
                throw new AssertionError("no scope expected");
            }

            public Scope site(SiteRef site) {
                throw new AssertionError("no scope expected");
            }

            public Scope siteInBackground(String siteId) {
                throw new AssertionError("no scope expected");
            }

            public Scope app() {
                throw new AssertionError("no scope expected");
            }
        };
        return (DatabaseConsumer me) -> db;
    }

    static GuestbookStore store() {
        return new GuestbookStore(unavailable(), new GuestbookConsumer());
    }

    static RouteRequest post(Map<String, List<String>> form, String ip) {
        return new RouteRequest(SITE, Locale.ENGLISH, "POST", GuestbookEndpoints.SIGN_PATH, Map.of(), Map.of(),
                new byte[0], form, ip, "https://acme.test");
    }

    static String location(RouteResponse r) {
        assertThat(r.status()).isEqualTo(303);
        assertThat(r.headers()).containsEntry("Cache-Control", "no-store");
        return r.headers().get("Location");
    }

    @Test
    void theGuardsAnswerBeforeTheDatabaseIsTouched() {
        AtomicLong clock = new AtomicLong(1_000_000);
        GuestbookEndpoints endpoints = new GuestbookEndpoints(store(),
                new FakePluginSettings(), captcha(false, ""), clock::get);
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Bot"), "message", List.of("x"),
                "website", List.of("spam"), "return", List.of("/hello")), "1.1.1.1"))))
                .isEqualTo("/hello?guestbook=thanks");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of(""), "message", List.of("x")), "1.1.1.1"))))
                .isEqualTo("/?guestbook=invalid");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("n".repeat(81)), "message",
                List.of("x")), "1.1.1.1")))).endsWith("invalid");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("a\u0000b")),
                "1.1.1.1")))).as("a NUL byte is invalid, not a database outage").endsWith("invalid");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("a\u001bb")),
                "1.1.1.1")))).endsWith("invalid");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message",
                List.of("line one\r\nline\ttwo")), "3.3.3.3")))).as("line breaks and tabs are a message's own")
                .endsWith("unavailable");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x"),
                "return", List.of("https://evil.example/")), "1.1.1.1")))).isEqualTo("/?guestbook=unavailable");
        for (int i = 0; i < GuestbookEndpoints.SIGN_PER_WINDOW - 1; i++) {
            endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x")), "1.1.1.1"));
        }
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x")),
                "1.1.1.1")))).endsWith("limited");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x")),
                "2.2.2.2")))).endsWith("unavailable");
        clock.addAndGet(GuestbookEndpoints.WINDOW_MILLIS);
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x")),
                "1.1.1.1")))).endsWith("unavailable");
    }

    @Test
    void aSiteWithACaptchaRefusesASignatureWithoutTheSolvedChallenge() {
        GuestbookEndpoints endpoints = new GuestbookEndpoints(store(), new FakePluginSettings(),
                captcha(true, "solved"));
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x")),
                "1.1.1.1")))).endsWith("guestbook=captcha");
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x"),
                "g-recaptcha-response", List.of("wrong")), "1.1.1.1")))).endsWith("guestbook=captcha");
        // Solved: on to the database (unavailable in this test).
        assertThat(location(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x"),
                "g-recaptcha-response", List.of("solved")), "1.1.1.1")))).endsWith("guestbook=unavailable");
    }

    @Test
    void theEntriesEndpointIsNeverCachedAndSaysWhenTheDatabaseIsAway() {
        GuestbookEndpoints endpoints = new GuestbookEndpoints(store(),
                new FakePluginSettings(), captcha(false, ""));
        RouteResponse r = endpoints.handle(new RouteRequest(SITE, Locale.ENGLISH, "GET",
                GuestbookEndpoints.ENTRIES_PATH, Map.of(), Map.of(), new byte[0]));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.headers()).containsEntry("Cache-Control", "no-store");
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("\"available\":false");
    }

    @Test
    void aSiteThatTurnedTheGuestbookOffAnswersNotFound() {
        GuestbookEndpoints endpoints = new GuestbookEndpoints(store(),
                new FakePluginSettings().site("acme", Map.of("enabled", false)), captcha(false, ""));
        assertThat(endpoints.handle(post(Map.of("name", List.of("Ana"), "message", List.of("x")), "1.1.1.1"))
                .status()).isEqualTo(404);
    }
}
