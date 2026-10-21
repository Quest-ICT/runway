package mx.quest.contact;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code contact} content widget renders the form inline on a normal page and re-renders the submit outcome
 * from the {@code ?contact=…} query the endpoint redirects back with. All rendering (form, thank-you, error
 * re-render, CAPTCHA, escaping) lives here — the endpoint only stores/sends/redirects. Settings and messages come
 * from the SPI services (testkit fakes).
 */
class ContactWidgetTest {

    private static final SiteView ACME = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);

    private FakePluginSettings settings;
    private FakeSiteServices kernel;
    private final AtomicReference<CaptchaWidget> challenge = new AtomicReference<>(CaptchaWidget.inactive());
    private ContactWidget widget;

    @BeforeEach
    void setUp() {
        settings = new FakePluginSettings().defaults(Map.of("enabled", true));
        kernel = new FakeSiteServices().site(ACME);
        CaptchaService captcha = new CaptchaService() {
            @Override
            public CaptchaWidget widgetForSite(String siteId) {
                return challenge.get();
            }

            @Override
            public boolean verify(String siteId, String token, String clientIp) {
                return false;
            }
        };
        widget = new ContactWidget(settings, kernel, captcha);
    }

    private String render(Map<String, String> query) {
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        return widget.render(new WidgetView(ACME, page, Locale.ENGLISH, "contact", false, Map.of(), query)).html();
    }

    @Test
    void theFormPostsAUrlEncodedBodyToTheAnonymousSubmitRoute() {
        String html = render(Map.of());
        assertTrue(html.contains("method=\"post\""), "the form POSTs — its fields never ride a URL: " + html);
        assertFalse(html.contains("method=\"get\""));
        assertTrue(html.contains("action=\"/contact/submit\""), "to the anonymous submit route");
        assertTrue(html.contains("enctype=\"application/x-www-form-urlencoded\""),
                "the only body an anonymous route accepts");
        assertFalse(html.contains("_csrf"), "no session token: the route is CSRF-exempt by declaration");
    }

    @Test
    void aBareRenderShowsTheFormWithTheHoneypotAndTheReturnPath() {
        String html = render(Map.of());
        assertTrue(html.contains("name=\"message\""), "the message field is present");
        assertTrue(html.contains("name=\"website\""), "the honeypot field is present");
        // Lands the visitor back on this page (its slug path) after submit; a moved page is caught server-side by Referer.
        assertTrue(html.contains("name=\"return\" value=\"/home\""), "the return path is the page the widget lives on");
    }

    @Test
    void theReturnPathIsTheRequestPathWhenTheHostProvidesIt() {
        // A blog entry (a content source's page) renders this widget from the master chrome: its PageMeta slug
        // is a composite identity ("blog:my-post"), NOT a path — the request path from the view must win.
        PageMeta entry = new PageMeta("blog:my-post", "My post", "", List.of(Locale.ENGLISH), Locale.ENGLISH,
                true, false, "blog");
        String html = widget.render(new WidgetView(ACME, entry, Locale.ENGLISH, "contact", true, Map.of(),
                Map.of(), "/es/blog/mi-entrada")).html();
        assertTrue(html.contains("name=\"return\" value=\"/es/blog/mi-entrada\""),
                "the return path is the URL as served — never built from the composite slug");
        assertFalse(html.contains("/blog:my-post"), "the composite identity never leaks into a path");
    }

    @Test
    void labelsComeFromTheSitesMessagesAndAreEscaped() {
        kernel.message("acme", "contact.form.submit", "Envoyer <maintenant>");
        String html = render(Map.of());
        assertTrue(html.contains(">Envoyer &lt;maintenant&gt;</button>"), "Messages.site for the label: " + html);
    }

    @Test
    void theSentOutcomeShowsAThankYouNotTheForm() {
        String html = render(Map.of("contact", "sent"));
        assertTrue(html.contains("rw-contact-success"), "a thank-you is shown");
        assertTrue(html.contains("Thanks — your message has been sent."), "the default thank-you text");
        assertFalse(html.contains("name=\"message\""), "the form is replaced by the confirmation");
    }

    @Test
    void theQueuedOutcomeThanksAndNotesTheDelayedDeliveryInsteadOfShowingTheFormAgain() {
        String html = render(Map.of("contact", "queued"));
        assertTrue(html.contains("rw-contact-success"), "the message was stored: thank the visitor");
        assertTrue(html.contains("email delivery is delayed"), "and say the email is late: " + html);
        assertFalse(html.contains("name=\"message\""), "no empty form inviting a duplicate submission");
        assertFalse(render(Map.of("contact", "sent")).contains("delayed"), "a plain success carries no note");
    }

    @Test
    void theConfiguredThankYouWinsAndIsEscaped() {
        settings.site("acme", Map.of("successMessage", "<b>x</b>"));
        String html = render(Map.of("contact", "sent"));
        assertFalse(html.contains("<b>x</b>"), "the configured thank-you is HTML-escaped");
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"));
    }

    @Test
    void anInvalidOutcomeReRendersTheFormWithAnError() {
        String html = render(Map.of("contact", "invalid"));
        assertTrue(html.contains("rw-contact-error"), "the validation error is surfaced");
        assertTrue(html.contains("name=\"message\""), "the form re-renders so the visitor can retry");
    }

    @Test
    void aCaptchaOutcomeReRendersTheFormWithAnError() {
        String html = render(Map.of("contact", "captcha"));
        assertTrue(html.contains("Please complete the challenge"), "the challenge error is surfaced");
        assertTrue(html.contains("name=\"message\""), "the form re-renders so the visitor can retry");
    }

    @Test
    void aConfiguredChallengeRendersOnTheForm() {
        challenge.set(new CaptchaWidget(true, "g-recaptcha-response", "<div class=\"g-recaptcha\" data-sitekey=\"KEY\"></div>"));
        String html = render(Map.of());
        assertTrue(html.contains("data-sitekey=\"KEY\""), "the site's CAPTCHA renders inline");
        assertTrue(html.indexOf("data-sitekey") < html.indexOf("</form>"), "inside the form, so its token is posted");
    }

    @Test
    void aDisabledContactRendersNothing() {
        settings.site("acme", Map.of("enabled", false));
        assertTrue(render(Map.of()).isEmpty(), "a disabled contact widget renders an empty fragment");
    }
}
