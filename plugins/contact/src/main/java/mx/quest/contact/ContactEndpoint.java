package mx.quest.contact;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SitePages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The contact plugin's tiny request-handling surface — it owns <b>no visible page</b>. The contact form lives on a
 * normal, editable site page (via the {@link ContactWidget} anchor; the operator installs the ready-made one from
 * the plugin settings). This endpoint only:
 * <ul>
 *   <li><b>{@code POST /contact/submit}</b> — an <b>anonymous</b> route ({@code Route.anonymousPost}): the kernel
 *       exempts exactly this method and path from CSRF (a public form on a cacheable page has no session token),
 *       hands it only {@code application/x-www-form-urlencoded} bodies and runs it with no principal. The fields
 *       are read from the <b>form body only</b> — a field put in the query string is ignored, so a visitor's
 *       personal data never has to travel in a URL. It stores + emails the submission, then answers {@code 303}
 *       back to the page the form lives on (the {@code return} field, or the {@code Referer}) with a
 *       {@code ?contact=sent|invalid|captcha|queued} flag the widget re-renders the outcome from. The abuse guards
 *       stay in front of the store: a filled honeypot fakes success (stores/sends nothing), an invalid submission
 *       or an unsolved CAPTCHA (verified with the kernel-gated {@code clientIp}) redirects with an error flag, and
 *       the platform's per-address rate limit (the protection plugin's edge guard) applies to this path like any
 *       other. A valid submission is <b>stored first</b> ({@link ContactStore}) and then <b>emailed</b> over the
 *       site's own relay, so a send failure never loses the message. Any other method on this path is a 404.</li>
 *   <li><b>{@code POST /contact/install-page}</b> — operator-gated ({@code contact|settings}): installs a real,
 *       editable page carrying the contact-form widget through {@link SitePages#install}, which respects the
 *       site's editorial level and never overwrites; the operator is told which of the outcomes happened.</li>
 *   <li><b>{@code POST /contact/smtp-test}</b> — operator-gated SMTP self-test; an optional {@code to} field of the
 *       form body overrides the recipient.</li>
 * </ul>
 * The two operator actions change something (a page is created, an email is sent), so they are <b>ordinary,
 * non-anonymous {@code POST} routes</b>: the kernel's tenant chain demands the console session's CSRF token on them
 * (the admin fragment's forms carry it), which is what stops another web site from triggering them through a
 * signed-in operator's browser with a link or an {@code <img>}. A {@code GET} on either path matches no route and
 * changes nothing (the kernel answers 404); {@link #handle} refuses any other method too, as a second line.
 * Neither action reads the query string.
 * Honors the per-site {@code enabled} setting (disabled ⇒ a submission is a no-op 404). All redirect targets are
 * validated as same-origin local paths.
 */
public class ContactEndpoint implements EndpointContributor {

    /** The anonymous form-submission route; the {@link ContactForm} posts here. */
    static final String SUBMIT_PATH = "/contact/submit";
    static final String INSTALL_PATH = "/contact/install-page";
    static final String SMTP_TEST_PATH = "/contact/smtp-test";
    /** The slug/path the ready-made contact page is installed at (then owned + movable by the operator). */
    static final String INSTALLED_PAGE_SLUG = "contact";
    static final String SETTINGS_PERMISSION = "contact|settings";

    private static final Logger log = LoggerFactory.getLogger(ContactEndpoint.class);

    private final ContactStore store;
    private final ContactMailer mailer;
    private final PluginSettings settings;
    private final Messages messages;
    private final AccessCheck access;
    private final CaptchaService captcha;
    private final SitePages pages;

    public ContactEndpoint(ContactStore store, ContactMailer mailer, PluginSettings settings, Messages messages,
                           AccessCheck access, CaptchaService captcha, SitePages pages) {
        this.store = store;
        this.mailer = mailer;
        this.settings = settings;
        this.messages = messages;
        this.access = access;
        this.captcha = captcha;
        this.pages = pages;
    }

    @Override
    public String key() {
        return "contact/form";
    }

    @Override
    public List<Route> routes() {
        // No public /contact PAGE — the form lives on a normal editable page. These are the submit handler + the
        // two operator actions; all safeDefault (noindex/no-store), never advertised or crawled. The operator
        // actions are POST and NOT anonymous: they change state, so the kernel's CSRF check must apply to them.
        return List.of(
                Route.anonymousPost(SUBMIT_PATH, SurfacePosture.safeDefault()),
                new Route("POST", INSTALL_PATH, Realm.TENANT, SurfacePosture.safeDefault()),
                new Route("POST", SMTP_TEST_PATH, Realm.TENANT, SurfacePosture.safeDefault()));
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        // Every route here is POST-only; the kernel dispatches by method + path, and this check is the second line.
        if (!"POST".equalsIgnoreCase(req.method())) {
            return RouteResponse.notFound();
        }
        return switch (req.path()) {
            case SUBMIT_PATH -> handleSubmit(req);
            case INSTALL_PATH -> handleInstallPage(req);
            case SMTP_TEST_PATH -> handleSmtpTest(req);
            default -> RouteResponse.notFound();
        };
    }

    /** Stores + emails a submission, then redirects back to the form's page with an outcome flag. */
    private RouteResponse handleSubmit(RouteRequest req) {
        String siteId = req.site().id();
        if (!settings.site(siteId).bool("enabled", true)) {
            return RouteResponse.notFound();
        }
        String back = resolveReturn(req);
        String name = formField(req, "name");
        String email = formField(req, "email");
        String message = formField(req, "message");
        String honeypot = formField(req, "website");

        if (!honeypot.isBlank()) {
            return redirect(back, "sent"); // a bot filled the trap — fake success, store/send nothing
        }
        if (message.isBlank() || !isEmail(email)) {
            return redirect(back, "invalid");
        }
        CaptchaWidget widget = captcha.widgetForSite(siteId);
        if (widget.active() && !captcha.verify(siteId, formField(req, widget.tokenField()), req.clientIp())) {
            return redirect(back, "captcha");
        }
        // Belt-and-suspenders: STORE first (never lose a message), THEN email over the site's relay.
        store.add(siteId, name, email, message, System.currentTimeMillis());
        MailSendResult sent = mailer.send(siteId, req.site().displayName(), name, email, message);
        // "queued" ⇒ stored but the configured relay failed (an unobtrusive "delivery delayed" note); NOT_CONFIGURED
        // is silent (the site simply stores messages) — so only a real relay failure downgrades to "queued".
        boolean delayed = sent != null && !sent.wasSent()
                && sent.status() != MailSendResult.Status.NOT_CONFIGURED;
        return redirect(back, delayed ? "queued" : "sent");
    }

    /**
     * Installs the ready-made, editable contact page (idempotent). {@code POST} only, CSRF-checked by the kernel;
     * operator-gated, failing closed with a 404.
     */
    private RouteResponse handleInstallPage(RouteRequest req) {
        if (!access.may(SETTINGS_PERMISSION)) {
            return RouteResponse.notFound();
        }
        String siteId = req.site().id();
        Locale siteDefault = req.site().defaultLocale();
        String title = messages.site(siteId, siteDefault, "contact.page.title", "Contact");
        StarterPage page = new StarterPage(INSTALLED_PAGE_SLUG, "/" + INSTALLED_PAGE_SLUG,
                Map.of(siteDefault.getLanguage(), title), installedPageHtml(title), installedPageLogic(), false);
        InstallResult result;
        try {
            result = pages.install(siteId, page);
        } catch (RuntimeException e) {
            // IllegalStateException: a path another page serves, an unknown site, a failed write (no stray draft
            // is left behind). Anything else is equally "not installed" to the operator.
            log.warn("contact page install failed for site {}: {}", siteId, e.toString());
            return RouteResponse.text(500, console("contact.page.failed", "Could not install the contact page."));
        }
        return RouteResponse.text(200, describe(result));
    }

    /** The operator's words for each install outcome; an outcome added in a later SPI MINOR gets a neutral line. */
    private String describe(InstallResult result) {
        String path = result.path();
        return switch (result.outcome()) {
            case PUBLISHED -> console("contact.page.installed", "Contact page installed at") + " " + path;
            case PENDING_REVIEW -> console("contact.page.pending",
                    "The contact page was created and is waiting in the review queue:") + " " + path;
            case DRAFT_BLOCKED -> {
                StringBuilder text = new StringBuilder(console("contact.page.blocked",
                        "The contact page was created as a draft because the quality checks blocked publishing; "
                                + "fix it in the page editor:")).append(' ').append(path);
                for (Finding finding : result.blocking()) {
                    text.append("\n- ").append(finding.code()).append(": ").append(finding.message());
                }
                yield text.toString();
            }
            case ALREADY_EXISTS -> console("contact.page.exists", "A contact page already exists at") + " " + path;
            default -> console("contact.page.other", "The contact page install finished:") + " "
                    + result.outcome() + " " + path;
        };
    }

    /**
     * The operator-only SMTP self-test ({@code POST}, CSRF-checked by the kernel) — fails closed unless the caller
     * holds {@code contact|settings}.
     */
    private RouteResponse handleSmtpTest(RouteRequest req) {
        if (!access.may(SETTINGS_PERMISSION)) {
            return RouteResponse.notFound();
        }
        // The override recipient comes from the form body only — never the query string.
        MailSendResult result = mailer.sendTest(req.site().id(), req.site().displayName(), formField(req, "to"));
        String text = switch (result.status()) {
            case SENT -> console("contact.test.ok", "Test message sent.");
            case NOT_CONFIGURED -> console("contact.test.notConfigured", "No SMTP relay is configured for this site.");
            case PROVIDER_MISSING -> console("contact.test.providerMissing",
                    "Mail libraries are not available on the server.");
            case FAILED -> console("contact.test.failed", "Test message failed:") + " " + result.detail();
        };
        return RouteResponse.text(result.wasSent() ? 200 : 502, text);
    }

    /** The page the form lives on: the same-origin {@code return} field, else the {@code Referer} path, else "/". */
    private static String resolveReturn(RouteRequest req) {
        String fromField = safeLocalPath(formField(req, "return"));
        if (fromField != null) {
            return fromField;
        }
        String fromReferer = safeLocalPath(refererPath(req.headers().get("referer")));
        return fromReferer != null ? fromReferer : "/";
    }

    /** The path (with query stripped) of a Referer, or {@code null} when absent. */
    private static String refererPath(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        int scheme = referer.indexOf("://");
        String path = referer;
        if (scheme >= 0) {
            int slash = referer.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : referer.substring(slash);
        }
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    /**
     * A {@code 303 See Other} back to {@code path} carrying only the outcome flag (never a submitted value); the
     * browser follows it with a GET and the widget on that page re-renders from the flag (post/redirect/get, so a
     * reload never resubmits).
     */
    private static RouteResponse redirect(String path, String outcome) {
        String location = path + (path.contains("?") ? "&" : "?") + "contact=" + outcome;
        return new RouteResponse(303, Map.of("Location", location), new byte[0]);
    }

    /** Reduces a candidate to a safe same-origin path (a single leading "/", no scheme/backslash/space/control). */
    private static String safeLocalPath(String p) {
        if (p == null || p.isBlank() || !p.startsWith("/") || p.startsWith("//") || p.contains("://")) {
            return null;
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c == '\\' || c == ' ') {
                return null;
            }
        }
        return p;
    }

    /**
     * The body of the installed page: chrome seams ({@code head}/{@code #site-header}/{@code #site-footer}, filled
     * from the master by {@link #installedPageLogic()}) and a {@code #content} carrying the contact-form widget
     * anchor. The anchor bears both an {@code id} and {@code data-widget="contact/form"} on one element (the
     * widget-binding contract), so the host renders the form in place.
     */
    private static String installedPageHtml(String title) {
        String t = Html.escape(title);
        return "<!DOCTYPE html>\n<html>\n<head><title>" + t + "</title></head>\n<body>\n"
                + "<header id=\"site-header\"></header>\n<main id=\"content\">\n"
                + "  <h1 id=\"contact-page-title\">" + t + "</h1>\n"
                + "  <div id=\"contact-form\" data-widget=\"contact/form\"></div>\n"
                + "</main>\n<footer id=\"site-footer\"></footer>\n</body>\n</html>\n";
    }

    /** The decoupled logic of the installed page: pulls the master chrome via {@code ${master}} (never a literal). */
    private static String installedPageLogic() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<thlogic>\n"
                + "    <attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>\n"
                + "    <attr sel=\"#site-header\" th:replace=\"~{${master} :: #site-header}\"/>\n"
                + "    <attr sel=\"#site-footer\" th:replace=\"~{${master} :: #site-footer}\"/>\n"
                + "    <attr sel=\"#contact-page-title\" th:text=\"${pageTitle}\"/>\n"
                + "</thlogic>\n";
    }

    private static boolean isEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 1 && value.indexOf('.', at) > at;
    }

    /** A field of the form BODY (never the query string), stripped; "" when absent. */
    private static String formField(RouteRequest req, String name) {
        List<String> values = req.form().get(name);
        return (values == null || values.isEmpty()) ? "" : values.get(0).strip();
    }

    private String console(String code, String fallback) {
        return messages.console(code, fallback);
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("endpoint.tenant", "contact.form");
    }
}
