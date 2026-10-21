package mx.quest.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.Severity;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contact plugin's request surface — it owns <b>no visible page</b>. Covered here: the anonymous
 * {@code POST /contact/submit} handler (303 back to the form's page with a {@code ?contact=…} outcome; POST only,
 * fields from the body only; store-then-send ordering; honeypot, invalid and CAPTCHA gating with the gated client
 * IP; return-path safety), the operator-gated {@code POST /contact/install-page} with each {@code SitePages}
 * outcome, and the operator-gated {@code POST /contact/smtp-test}. Both operator actions change state, so they are
 * non-anonymous POST routes (the kernel's CSRF check applies) and a GET changes nothing. The form's HTML is covered
 * in {@link ContactWidgetTest}; the console forms that carry the CSRF token in {@link ContactAdminTemplateTest}.
 */
class ContactEndpointTest {

    private static final SiteView ACME = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);
    private static final String CLIENT_IP = "203.0.113.7";

    private ContactStore store;
    private ContactMailer mailer;
    private FakePluginSettings settings;
    private FakeSiteServices kernel;
    private CaptchaService captcha;
    private ContactEndpoint endpoint;

    @BeforeEach
    void setUp() {
        store = mock(ContactStore.class);
        mailer = mock(ContactMailer.class);
        when(mailer.send(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MailSendResult.sent());
        settings = new FakePluginSettings().defaults(Map.of("enabled", true));
        kernel = new FakeSiteServices().site(ACME).current("acme");
        captcha = mock(CaptchaService.class);
        // Default: no challenge configured (submissions flow through the non-CAPTCHA path).
        when(captcha.widgetForSite(any())).thenReturn(CaptchaWidget.inactive());
        endpoint = new ContactEndpoint(store, mailer, settings, kernel, kernel, captcha, kernel);
    }

    private static RouteRequest request(String method, String path, Map<String, List<String>> query,
                                        Map<String, List<String>> form, Map<String, String> headers) {
        return new RouteRequest(ACME, Locale.ENGLISH, method, path, query, headers, new byte[0], form, CLIENT_IP,
                "https://acme.test");
    }

    /** A POST /contact/submit with the given BODY fields; carries a {@code return} of /home. */
    private RouteResponse submit(Map<String, List<String>> fields) {
        Map<String, List<String>> form = new HashMap<>(fields);
        form.putIfAbsent("return", List.of("/home"));
        return endpoint.handle(request("POST", "/contact/submit", Map.of(), form, Map.of()));
    }

    private static Map<String, List<String>> valid() {
        return Map.of("name", List.of("Ann"), "email", List.of("ann@example.com"),
                "message", List.of("Hello there"), "website", List.of(""));
    }

    private static Map<String, List<String>> with(Map<String, List<String>> base, String key, String value) {
        Map<String, List<String>> copy = new HashMap<>(base);
        copy.put(key, List.of(value));
        return copy;
    }

    private static String location(RouteResponse res) {
        return res.headers().get("Location");
    }

    private static String text(RouteResponse res) {
        return new String(res.body(), StandardCharsets.UTF_8);
    }

    private void verifyNothingStoredOrSent() {
        verify(store, never()).add(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(mailer, never()).send(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ---- routes ----------------------------------------------------------------------------------------------

    @Test
    void theSubmitRouteIsAnAnonymousPostAndTheOperatorActionsAreNot() {
        List<Route> routes = endpoint.routes();
        Route submit = routes.stream().filter(r -> r.pathPattern().equals("/contact/submit")).findFirst().orElseThrow();
        assertEquals("POST", submit.method());
        assertTrue(submit.anonymous(), "declared anonymous: the kernel exempts exactly this POST from CSRF");
        assertEquals(1, routes.stream().filter(r -> r.pathPattern().equals("/contact/submit")).count(),
                "no GET submit route remains");
        for (Route r : routes) {
            if (!r.pathPattern().equals("/contact/submit")) {
                assertFalse(r.anonymous(), "the operator actions keep the principal (and the CSRF rule): " + r);
            }
        }
    }

    @Test
    void everyRouteIsAPostSoNoLinkOrImageCanChangeState() {
        List<Route> routes = endpoint.routes();
        assertEquals(List.of("/contact/submit", "/contact/install-page", "/contact/smtp-test"),
                routes.stream().map(Route::pathPattern).toList());
        for (Route r : routes) {
            assertEquals("POST", r.method(), "a GET route would let a cross-site link trigger it: " + r);
        }
    }

    // ---- POST /contact/submit --------------------------------------------------------------------------------

    @Test
    void aValidPostRedirects303SentAndStoresAndEmails() {
        RouteResponse res = submit(valid());
        assertEquals(303, res.status(), "post/redirect/get");
        assertEquals("/home?contact=sent", location(res), "it lands back on the form's page with a success flag");
        verify(store).add(eq("acme"), eq("Ann"), eq("ann@example.com"), eq("Hello there"), anyLong());
        verify(mailer).send(eq("acme"), eq("Acme"), eq("Ann"), eq("ann@example.com"), eq("Hello there"));
    }

    @Test
    void theRedirectCarriesTheOutcomeOnlyNeverASubmittedValue() {
        String where = location(submit(valid()));
        for (String personal : List.of("Ann", "ann", "example.com", "Hello")) {
            assertFalse(where.contains(personal), "no personal data in the Location: " + where);
        }
    }

    @Test
    void aGetSubmissionIsNotAcceptedAndStoresNothing() {
        Map<String, List<String>> query = with(valid(), "return", "/home");
        RouteResponse res = endpoint.handle(request("GET", "/contact/submit", query, Map.of(), Map.of()));
        assertEquals(404, res.status(), "1.x's GET submission is gone: only the anonymous POST is handled");
        verifyNothingStoredOrSent();
    }

    @Test
    void fieldsInTheQueryStringAreIgnoredOnAPost() {
        Map<String, List<String>> query = valid();
        RouteResponse res = endpoint.handle(request("POST", "/contact/submit", query,
                Map.of("return", List.of("/home")), Map.of()));
        assertEquals("/home?contact=invalid", location(res), "personal data is read from the body only");
        verifyNothingStoredOrSent();
    }

    @Test
    void aFilledHoneypotFakesSuccessWithoutStoringOrEmailing() {
        RouteResponse res = submit(with(valid(), "website", "http://spam.example"));
        assertEquals("/home?contact=sent", location(res), "the bot sees a fake success");
        verifyNothingStoredOrSent();
    }

    @Test
    void anInvalidEmailRedirectsWithTheInvalidFlag() {
        assertEquals("/home?contact=invalid", location(submit(with(valid(), "email", "not-an-email"))));
        verifyNothingStoredOrSent();
    }

    @Test
    void aBlankMessageRedirectsWithTheInvalidFlag() {
        assertEquals("/home?contact=invalid", location(submit(with(valid(), "message", "   "))));
        verifyNothingStoredOrSent();
    }

    @Test
    void anActiveCaptchaWithoutATokenRefusesThePost() {
        when(captcha.widgetForSite(any())).thenReturn(new CaptchaWidget(
                true, "g-recaptcha-response", "<div class=\"g-recaptcha\"></div>"));
        when(captcha.verify(any(), any(), any())).thenReturn(false);
        RouteResponse res = submit(valid());
        assertEquals("/home?contact=captcha", location(res));
        verify(captcha).verify("acme", "", CLIENT_IP);
        verifyNothingStoredOrSent();
    }

    @Test
    void anActiveCaptchaIsVerifiedWithTheBodyTokenAndTheGatedClientIp() {
        when(captcha.widgetForSite(any())).thenReturn(new CaptchaWidget(
                true, "g-recaptcha-response", "<div class=\"g-recaptcha\"></div>"));
        when(captcha.verify(eq("acme"), eq("good-token"), eq(CLIENT_IP))).thenReturn(true);
        RouteResponse res = submit(with(valid(), "g-recaptcha-response", "good-token"));
        assertEquals("/home?contact=sent", location(res));
        verify(store).add(eq("acme"), eq("Ann"), eq("ann@example.com"), eq("Hello there"), anyLong());
    }

    @Test
    void aCaptchaTokenOnlyInTheQueryStringDoesNotCount() {
        when(captcha.widgetForSite(any())).thenReturn(new CaptchaWidget(
                true, "g-recaptcha-response", "<div class=\"g-recaptcha\"></div>"));
        when(captcha.verify(eq("acme"), eq("good-token"), any())).thenReturn(true);
        Map<String, List<String>> form = with(valid(), "return", "/home");
        RouteResponse res = endpoint.handle(request("POST", "/contact/submit",
                Map.of("g-recaptcha-response", List.of("good-token")), form, Map.of()));
        assertEquals("/home?contact=captcha", location(res));
        verifyNothingStoredOrSent();
    }

    @Test
    void anActiveCaptchaThatFailsRedirectsWithTheCaptchaFlag() {
        when(captcha.widgetForSite(any())).thenReturn(new CaptchaWidget(
                true, "g-recaptcha-response", "<div class=\"g-recaptcha\"></div>"));
        when(captcha.verify(any(), any(), any())).thenReturn(false);
        assertEquals("/home?contact=captcha", location(submit(with(valid(), "g-recaptcha-response", "bad"))));
        verifyNothingStoredOrSent();
    }

    @Test
    void disabledContactReturns404() {
        settings.site("acme", Map.of("enabled", false));
        assertEquals(404, submit(valid()).status());
        verifyNothingStoredOrSent();
    }

    @Test
    void aFailedSendRedirectsQueuedButStillStores() {
        when(mailer.send(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MailSendResult.failed("relay refused"));
        assertEquals("/home?contact=queued", location(submit(valid())), "a real relay failure is a delayed note");
        verify(store).add(eq("acme"), eq("Ann"), eq("ann@example.com"), eq("Hello there"), anyLong());
    }

    @Test
    void anUnconfiguredRelayRedirectsSentSilentlyAndStores() {
        when(mailer.send(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MailSendResult.notConfigured());
        assertEquals("/home?contact=sent", location(submit(valid())), "a store-only site never shows a delivery note");
        verify(store).add(eq("acme"), eq("Ann"), eq("ann@example.com"), eq("Hello there"), anyLong());
    }

    @Test
    void theRefererIsUsedWhenNoReturnFieldIsGiven() {
        RouteResponse res = endpoint.handle(request("POST", "/contact/submit", Map.of(), valid(),
                Map.of("Referer", "https://acme.test/contact-us?x=1")));
        assertEquals("/contact-us?contact=sent", location(res), "it lands back on the Referer page (query stripped)");
    }

    @Test
    void aForeignReturnFieldIsRejectedAndFallsBackToRoot() {
        RouteResponse res = submit(with(valid(), "return", "https://evil.example/steal"));
        assertEquals("/?contact=sent", location(res), "an off-origin return target is ignored (no open redirect)");
        assertEquals("/?contact=sent", location(submit(with(valid(), "return", "//evil.example/x"))));
    }

    // ---- /contact/install-page (operator-gated) ---------------------------------------------------------------

    private RouteResponse install() {
        return endpoint.handle(request("POST", "/contact/install-page", Map.of(), Map.of(), Map.of()));
    }

    @Test
    void theInstallPageIsHiddenFromCallersWithoutThePermission() {
        RouteResponse res = install();
        assertEquals(404, res.status(), "fail closed: the action is not revealed to the unauthorized");
        assertTrue(kernel.installs().isEmpty(), "nothing was installed");
    }

    @Test
    void aGetOnTheInstallPathInstallsNothingEvenForAnAuthorizedOperator() {
        kernel.grant("contact|settings");
        for (String method : List.of("GET", "HEAD", "PUT", "DELETE")) {
            RouteResponse res = endpoint.handle(request(method, "/contact/install-page", Map.of(), Map.of(), Map.of()));
            assertEquals(404, res.status(), method + " is not the install action");
        }
        assertTrue(kernel.installs().isEmpty(), "following a link (or loading an <img>) must not install the page");
    }

    @Test
    void theInstallMethodIsMatchedCaseInsensitively() {
        kernel.grant("contact|settings");
        RouteResponse res = endpoint.handle(request("post", "/contact/install-page", Map.of(), Map.of(), Map.of()));
        assertEquals(200, res.status());
        assertEquals(1, kernel.installs().size());
    }

    @Test
    void anInstallThatPublishesSaysWhereThePageIs() {
        kernel.grant("contact|settings").message("acme", "contact.page.title", "Contact us");
        RouteResponse res = install();
        assertEquals(200, res.status());
        assertEquals("Contact page installed at /contact", text(res));
        StarterPage page = kernel.installs().get(0);
        assertEquals("contact", page.slug(), "installed at the stable slug");
        assertEquals("/contact", page.path());
        assertEquals(Map.of("en", "Contact us"), page.titles(), "titled in the site's default language");
        assertFalse(page.noindex(), "a contact page is meant to be found");
        assertTrue(page.html().contains("data-widget=\"contact/form\""), "the page carries the widget anchor");
        assertTrue(page.html().contains("<title>Contact us</title>"));
        assertTrue(page.logic().contains("${master}"), "the chrome comes from the master");
    }

    @Test
    void anInstallOnAReviewedSiteSaysItIsWaitingForReview() {
        kernel.grant("contact|settings").nextInstall(InstallResult.Outcome.PENDING_REVIEW);
        RouteResponse res = install();
        assertEquals(200, res.status());
        assertTrue(text(res).contains("waiting in the review queue"), text(res));
        assertTrue(text(res).endsWith("/contact"));
    }

    @Test
    void anInstallBlockedByTheQualityChecksSaysItIsADraftAndWhy() {
        kernel.grant("contact|settings").nextInstall(InstallResult.Outcome.DRAFT_BLOCKED,
                new Finding(Severity.ERROR, "content.thin", "The page has almost no text."));
        RouteResponse res = install();
        assertEquals(200, res.status());
        assertTrue(text(res).contains("as a draft"), text(res));
        assertTrue(text(res).contains("- content.thin: The page has almost no text."), "the blocking finding: " + text(res));
    }

    @Test
    void aSecondInstallChangesNothingAndSaysWhereTheExistingPageIs() {
        kernel.grant("contact|settings").page("acme", "contact", "/hola");
        RouteResponse res = install();
        assertEquals(200, res.status(), "an existing page is not an error — the action is idempotent");
        assertEquals("A contact page already exists at /hola", text(res), "the page's current path, wherever it moved");
    }

    @Test
    void aFailedInstallIsA500WithTheOperatorsMessage() {
        SitePages failing = mock(SitePages.class);
        when(failing.install(any(), any())).thenThrow(new IllegalStateException("/contact is served by another page"));
        kernel.grant("contact|settings");
        ContactEndpoint withFailingPages = new ContactEndpoint(store, mailer, settings, kernel, kernel, captcha, failing);
        RouteResponse res = withFailingPages.handle(request("POST", "/contact/install-page", Map.of(), Map.of(), Map.of()));
        assertEquals(500, res.status());
        assertEquals("Could not install the contact page.", text(res));
    }

    @Test
    void theOperatorAnswersAreConsoleMessages() {
        kernel.grant("contact|settings").message(null, "contact.page.installed", "Página de contacto instalada en");
        assertEquals("Página de contacto instalada en /contact", text(install()),
                "in the operator's console language (Messages.console), not the visitor's");
    }

    // ---- /contact/smtp-test (operator-gated) ------------------------------------------------------------------

    private RouteResponse smtpTest(Map<String, List<String>> form) {
        return endpoint.handle(request("POST", "/contact/smtp-test", Map.of(), form, Map.of()));
    }

    @Test
    void theSmtpTestIsHiddenFromCallersWithoutThePermission() {
        assertEquals(404, smtpTest(Map.of()).status(), "fail closed: the surface is not revealed to the unauthorized");
        verifyNoInteractions(mailer);
    }

    @Test
    void theSmtpTestSendsForAnAuthorizedCaller() {
        kernel.grant("contact|settings");
        when(mailer.sendTest(eq("acme"), eq("Acme"), eq("me@example.com"))).thenReturn(MailSendResult.sent());
        RouteResponse res = smtpTest(Map.of("to", List.of(" me@example.com ")));
        assertEquals(200, res.status());
        assertEquals("Test message sent.", text(res));
    }

    @Test
    void theSmtpTestWithoutARecipientUsesTheConfiguredOne() {
        kernel.grant("contact|settings");
        when(mailer.sendTest(eq("acme"), eq("Acme"), eq(""))).thenReturn(MailSendResult.sent());
        assertEquals(200, smtpTest(Map.of()).status());
        verify(mailer).sendTest("acme", "Acme", "");
    }

    @Test
    void aGetOnTheSmtpTestPathSendsNothingEvenForAnAuthorizedOperator() {
        kernel.grant("contact|settings");
        RouteResponse res = endpoint.handle(request("GET", "/contact/smtp-test",
                Map.of("to", List.of("victim@example.com")), Map.of(), Map.of()));
        assertEquals(404, res.status(), "a cross-site link must not make the server send an email");
        verifyNoInteractions(mailer);
    }

    @Test
    void theSmtpTestRecipientIsReadFromTheBodyNeverTheQueryString() {
        kernel.grant("contact|settings");
        when(mailer.sendTest(anyString(), anyString(), any())).thenReturn(MailSendResult.sent());
        endpoint.handle(request("POST", "/contact/smtp-test", Map.of("to", List.of("victim@example.com")),
                Map.of(), Map.of()));
        verify(mailer).sendTest("acme", "Acme", "");
        verify(mailer, never()).sendTest(anyString(), anyString(), eq("victim@example.com"));
    }

    @Test
    void aFailedSmtpTestReportsA502() {
        kernel.grant("contact|settings");
        when(mailer.sendTest(anyString(), anyString(), any())).thenReturn(MailSendResult.failed("timeout"));
        RouteResponse res = smtpTest(Map.of());
        assertEquals(502, res.status(), "a failed test reports a bad-gateway status");
        assertTrue(text(res).contains("timeout"));
    }
}
