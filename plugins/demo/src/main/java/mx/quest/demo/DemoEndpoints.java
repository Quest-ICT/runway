package mx.quest.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code EndpointContributor} — plugin HTTP routes on a site's public host, without touching a servlet. The frozen
 * {@link RouteRequest} carries the resolved site, the negotiated locale, the query, the form body, lowercase headers,
 * the gated client IP and the request origin; each route declares a {@link SurfacePosture}. Routes are exact paths
 * on the tenant realm and must avoid the reserved kernel prefixes ({@code /__…}, {@code /login}, …).
 *
 * <ul>
 *   <li>{@code GET /demo/hello.txt} — public, {@code discoverable()}: a greeting localized with
 *       {@link Messages#site} in the request's negotiated locale.</li>
 *   <li>{@code GET /demo/echo.json} — public: echoes what the host put in the {@code RouteRequest}.</li>
 *   <li>{@code GET /demo/notes.json} — <b>permission-gated</b> with {@link AccessCheck}: only a signed-in user holding
 *       {@code runway-demo|manage-notes} gets the notes; anyone else gets {@code 403}. <b>When to use
 *       {@code AccessCheck}:</b> every time your code must decide whether the current user may do something — the
 *       menu and dashboard-card {@code permission:} keys only hide UI. It is fail-closed (no request, no principal,
 *       an undeclared key → {@code false}) and is the same bean as {@code @authz} in {@code @PreAuthorize} and
 *       templates.</li>
 *   <li>{@code POST /demo/feedback} — an <b>anonymous form route</b> ({@link Route#anonymousPost}): the public
 *       feedback form of {@link DemoFeedbackWidget} posts here with <b>no CSRF token</b>. <b>When to use the
 *       anonymous flag:</b> only for a form any visitor may submit with no account, placed on a page every visitor
 *       shares, which therefore cannot carry a per-session token (a contact form, a newsletter sign-up). The kernel
 *       exempts exactly this method and path from CSRF and makes that safe by running the handler with <b>no
 *       principal</b> ({@code AccessCheck.may} is {@code false} even for a signed-in administrator) and accepting only
 *       an {@code application/x-www-form-urlencoded} body. Keep your own abuse guards: here a honeypot field, a length
 *       cap, the site's CAPTCHA through {@link CaptchaService} when the site has configured one, and a per-address
 *       rate limit keyed by {@link RouteRequest#clientIp()} (the address after the kernel's trusted-proxy gate —
 *       never a header). Never use the flag for an action on something a signed-in user owns;
 *       those are CSRF-checked {@code POST}s (the demo's console screen, {@link DemoConsoleController}).</li>
 * </ul>
 */
public class DemoEndpoints implements EndpointContributor {

    static final String HELLO_PATH = "/demo/hello.txt";
    static final String ECHO_PATH = "/demo/echo.json";
    static final String NOTES_PATH = "/demo/notes.json";
    static final String FEEDBACK_PATH = "/demo/feedback";
    static final String PERMISSION = "runway-demo|manage-notes";
    /** Feedback messages one client address may send per {@link #WINDOW_MILLIS}. */
    static final int FEEDBACK_PER_WINDOW = 5;
    static final long WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(1);
    /** Above this many tracked addresses, stale ones are swept (bounds the limiter's memory). */
    private static final int MAX_TRACKED = 10_000;

    private final AccessCheck access;
    private final DemoNotesStore notes;
    private final Messages messages;
    private final PluginSettings settings;
    private final CaptchaService captcha;
    private final LongSupplier clockMillis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ConcurrentMap<String, Deque<Long>> recentFeedback = new ConcurrentHashMap<>();

    public DemoEndpoints(AccessCheck access, DemoNotesStore notes, Messages messages, PluginSettings settings,
                         CaptchaService captcha) {
        this(access, notes, messages, settings, captcha, System::currentTimeMillis);
    }

    DemoEndpoints(AccessCheck access, DemoNotesStore notes, Messages messages, PluginSettings settings,
                  CaptchaService captcha, LongSupplier clockMillis) {
        this.access = access;
        this.notes = notes;
        this.messages = messages;
        this.settings = settings;
        this.captcha = captcha;
        this.clockMillis = clockMillis;
    }

    @Override
    public String key() {
        return "runway-demo/endpoints";
    }

    @Override
    public List<Route> routes() {
        return List.of(
                new Route("GET", HELLO_PATH, Realm.TENANT, SurfacePosture.discoverable()),
                new Route("GET", ECHO_PATH, Realm.TENANT, SurfacePosture.safeDefault()),
                new Route("GET", NOTES_PATH, Realm.TENANT, SurfacePosture.safeDefault()),
                Route.anonymousPost(FEEDBACK_PATH, SurfacePosture.safeDefault()));
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        // The kernel dispatches by method + path; checking the method again is the second line of defence.
        return switch (req.method().toUpperCase(Locale.ROOT) + " " + req.path()) {
            case "GET " + HELLO_PATH -> hello(req);
            case "GET " + ECHO_PATH -> echo(req);
            case "GET " + NOTES_PATH -> notes(req);
            case "POST " + FEEDBACK_PATH -> feedback(req);
            default -> RouteResponse.notFound();
        };
    }

    private RouteResponse hello(RouteRequest req) {
        String greeting = messages.site(req.site().id(), req.locale(), "runwaydemo.hello",
                "Hello from the runway-demo plugin!") + "\n"
                + "site: " + req.site().displayName() + " (" + req.site().id() + ")\n"
                + "locale: " + req.locale().toLanguageTag() + "\n"
                + "baseUrl: " + (req.site().baseUrl() == null ? "(derived)" : req.site().baseUrl()) + "\n"
                + "origin: " + (req.origin().isEmpty() ? "(unknown)" : req.origin()) + "\n";
        return RouteResponse.text(200, greeting);
    }

    private RouteResponse echo(RouteRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", req.method());
        payload.put("path", req.path());
        payload.put("site", req.site().id());
        payload.put("locale", req.locale().toLanguageTag());   // negotiated: ?lang → cookie → Accept-Language → default
        payload.put("query", req.query());                     // the URL query string only
        payload.put("form", req.form());                       // a url-encoded body's fields (empty on a GET)
        payload.put("param.q", req.param("q").orElse(null));   // query first, then form
        payload.put("headers", req.headers());                 // lowercase names; no X-Forwarded-* ever
        payload.put("clientIp", req.clientIp());               // after the trusted-proxy gate; null when unknown
        payload.put("origin", req.origin());                   // scheme://host[:port]; "" when unknown
        return jsonResponse(200, payload);
    }

    private RouteResponse notes(RouteRequest req) {
        if (!access.may(PERMISSION)) {
            return RouteResponse.text(403, "Forbidden — requires the runway-demo \"manage-notes\" permission.\n");
        }
        List<Note> siteNotes = notes.list(req.site().id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site", req.site().id());
        payload.put("count", siteNotes.size());
        payload.put("notes", siteNotes);
        return jsonResponse(200, payload);
    }

    /**
     * Keeps a visitor's message as a note, then answers {@code 303} back to the form's page with an outcome flag
     * (never a submitted value). Fields are read from the form <b>body</b> only, so personal text never has to
     * travel in a URL. A failure to store propagates: the kernel answers {@code 500}, logs and counts it.
     */
    private RouteResponse feedback(RouteRequest req) {
        String siteId = req.site().id();
        if (!settings.site(siteId).bool("feedback.enabled", true)) {
            return RouteResponse.notFound();
        }
        String back = safeLocalPath(formField(req, "return"));
        if (!formField(req, "website").isEmpty()) {
            return redirect(back, "thanks");   // a bot filled the honeypot: pretend success, keep nothing
        }
        String message = formField(req, "message");
        if (message.isEmpty()) {
            return redirect(back, "invalid");
        }
        // The site's challenge, when it has one (the kernel's default answers "inactive"); verified with the gated
        // client address, as the provider expects the visitor's.
        CaptchaWidget challenge = captcha.widgetForSite(siteId);
        if (challenge.active() && !captcha.verify(siteId, formField(req, challenge.tokenField()), req.clientIp())) {
            return redirect(back, "captcha");
        }
        if (!allow(req.clientIp())) {
            return redirect(back, "limited");
        }
        notes.append(siteId, message, Note.ORIGIN_VISITOR);
        return redirect(back, "thanks");
    }

    /** A sliding-window limit per gated client address; an unknown address shares one bucket (fail-safe). */
    boolean allow(String clientIp) {
        long now = clockMillis.getAsLong();
        if (recentFeedback.size() > MAX_TRACKED) {
            recentFeedback.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() >= WINDOW_MILLIS;
                }
            });
        }
        Deque<Long> times = recentFeedback.computeIfAbsent(clientIp == null ? "unknown" : clientIp,
                k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= FEEDBACK_PER_WINDOW) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private static String formField(RouteRequest req, String name) {
        List<String> values = req.form().get(name);
        return values == null || values.isEmpty() ? "" : values.get(0).strip();
    }

    /** A same-origin local path (one leading "/", no scheme, backslash, whitespace or control character), else "/". */
    static String safeLocalPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            return "/";
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c == '\\' || c == 0x7f) {
                return "/";
            }
        }
        return path;
    }

    private static RouteResponse redirect(String path, String outcome) {
        String location = path + (path.contains("?") ? "&" : "?") + DemoFeedbackWidget.OUTCOME_PARAM + "=" + outcome;
        return new RouteResponse(303, Map.of("Location", location), new byte[0]);
    }

    private RouteResponse jsonResponse(int status, Object payload) {
        byte[] body = json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        return new RouteResponse(status, Map.of("Content-Type", "application/json; charset=utf-8"), body);
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("endpoint.tenant", "endpoint.json", "endpoint.anonymous-form");
    }
}
