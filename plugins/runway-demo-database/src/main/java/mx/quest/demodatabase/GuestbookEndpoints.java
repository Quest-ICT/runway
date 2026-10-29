package mx.quest.demodatabase;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import mx.quest.database.spi.DatabaseException;
import mx.quest.database.spi.PluginDatabase;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.PluginSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The guestbook's two public routes.
 *
 * <ul>
 *   <li>{@code POST /guestbook/sign} — an anonymous form route (no CSRF token: the form sits on a page every visitor
 *       shares). Its guards: a honeypot field, length caps, the site's CAPTCHA through {@link CaptchaService} when the
 *       site has one, a per-address sliding window keyed by the gated client address, and the per-site row cap the
 *       store applies in the same transaction. Fields come from the form body
 *       only; the answer is a {@code 303} back to the page with an outcome flag, never a submitted value.</li>
 *   <li>{@code GET /guestbook/entries.json} — the entries, a page at a time, with {@code Cache-Control: no-store}.
 *       The page itself renders only a static shell (the widget's output never depends on the rows), so the page
 *       stays cacheable and its ETag honest while new entries show at once.</li>
 * </ul>
 *
 * The site always comes from the request ({@link RouteRequest#site()}), never from a parameter.
 */
public class GuestbookEndpoints implements EndpointContributor {

    private static final Logger log = LoggerFactory.getLogger(GuestbookEndpoints.class);

    static final String SIGN_PATH = "/guestbook/sign";
    static final String ENTRIES_PATH = "/guestbook/entries.json";
    static final String OUTCOME_PARAM = "guestbook";
    static final int PAGE_SIZE = 20;
    static final int PAGE_MAX = 500;
    static final int SIGN_PER_WINDOW = 5;
    static final long WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final int MAX_TRACKED = 10_000;

    private final GuestbookStore store;
    private final PluginSettings settings;
    private final CaptchaService captcha;
    private final LongSupplier clockMillis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ConcurrentMap<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public GuestbookEndpoints(GuestbookStore store, PluginSettings settings, CaptchaService captcha) {
        this(store, settings, captcha, System::currentTimeMillis);
    }

    GuestbookEndpoints(GuestbookStore store, PluginSettings settings, CaptchaService captcha,
                       LongSupplier clockMillis) {
        this.store = store;
        this.settings = settings;
        this.captcha = captcha;
        this.clockMillis = clockMillis;
    }

    @Override
    public String key() {
        return "runway-demo-database/guestbook";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route("GET", ENTRIES_PATH, Realm.TENANT, SurfacePosture.safeDefault()),
                Route.anonymousPost(SIGN_PATH, SurfacePosture.safeDefault()));
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        return switch (req.method().toUpperCase(Locale.ROOT) + " " + req.path()) {
            case "GET " + ENTRIES_PATH -> entries(req);
            case "POST " + SIGN_PATH -> sign(req);
            default -> RouteResponse.notFound();
        };
    }

    static boolean enabled(PluginSettings settings, String siteId) {
        return settings.site(siteId).bool("enabled", true);
    }

    static int cap(PluginSettings settings, String siteId) {
        return (int) settings.site(siteId).integer("maxEntries", 500, 10, GuestbookStore.ENTRIES_CEILING);
    }

    private RouteResponse entries(RouteRequest req) {
        String siteId = req.site().id();
        if (!enabled(settings, siteId)) {
            return RouteResponse.notFound();
        }
        PluginDatabase db = store.db();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!db.available()) {
            payload.put("available", false);
            return json(503, payload);
        }
        int page = 0;
        try {
            page = Math.min(PAGE_MAX, Math.max(0, Integer.parseInt(req.param("page").orElse("0"))));
        } catch (NumberFormatException ignored) {
            // the first page
        }
        try {
            GuestbookStore.Page p = store.page(db.site(new SiteRef(siteId)), page, PAGE_SIZE);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (GuestbookStore.Entry e : p.entries()) {
                entries.add(Map.of("name", e.name(), "message", e.message(), "at", e.at().toString()));
            }
            payload.put("available", true);
            payload.put("page", p.page());
            payload.put("more", p.more());
            payload.put("entries", entries);
            return json(200, payload);
        } catch (DatabaseException e) {
            log.warn("runway-demo-database: the guestbook of site '{}' could not be read: {}", siteId,
                    e.getMessage());
            payload.put("available", false);
            return json(503, payload);
        }
    }

    private RouteResponse sign(RouteRequest req) {
        String siteId = req.site().id();
        if (!enabled(settings, siteId)) {
            return RouteResponse.notFound();
        }
        String back = safeLocalPath(field(req, "return"));
        if (!field(req, "website").isEmpty()) {
            return redirect(back, "thanks"); // a bot filled the honeypot: pretend success, keep nothing
        }
        String name = field(req, "name");
        String message = field(req, "message");
        if (name.isEmpty() || message.isEmpty() || name.length() > GuestbookStore.NAME_MAX
                || message.length() > GuestbookStore.MESSAGE_MAX || hasControl(name)
                || hasControlBesidesLines(message)) {
            return redirect(back, "invalid");
        }
        // The site's challenge, when it has one (the kernel's default answers "inactive"); verified with the gated
        // client address, as the provider expects the visitor's.
        CaptchaWidget challenge = captcha.widgetForSite(siteId);
        if (challenge.active() && !captcha.verify(siteId, field(req, challenge.tokenField()), req.clientIp())) {
            return redirect(back, "captcha");
        }
        if (!allow(req.clientIp())) {
            return redirect(back, "limited");
        }
        PluginDatabase db = store.db();
        if (!db.available()) {
            return redirect(back, "unavailable");
        }
        try {
            store.add(db.site(new SiteRef(siteId)), name, message, cap(settings, siteId));
        } catch (DatabaseException e) {
            log.warn("runway-demo-database: a guestbook entry for site '{}' was not kept: {}", siteId, e.getMessage());
            return redirect(back, "unavailable");
        }
        return redirect(back, "thanks");
    }

    /** A sliding-window limit per gated client address; an unknown address shares one bucket. */
    boolean allow(String clientIp) {
        long now = clockMillis.getAsLong();
        if (recent.size() > MAX_TRACKED) {
            recent.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() >= WINDOW_MILLIS;
                }
            });
        }
        Deque<Long> times = recent.computeIfAbsent(clientIp == null ? "unknown" : clientIp, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= SIGN_PER_WINDOW) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private static boolean hasControl(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }

    /**
     * A control character other than a line break or a tab. A message may span lines; anything else is refused
     * here, as "invalid", before the database: PostgreSQL rejects U+0000 in text, and the visitor would otherwise
     * be told the database is unavailable.
     */
    private static boolean hasControlBesidesLines(String s) {
        return s.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    }

    private static String field(RouteRequest req, String name) {
        List<String> values = req.form().get(name);
        return values == null || values.isEmpty() ? "" : values.get(0).strip();
    }

    /** A same-origin local path, else "/". */
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
        String location = path + (path.contains("?") ? "&" : "?") + OUTCOME_PARAM + "=" + outcome;
        return new RouteResponse(303, Map.of("Location", location, "Cache-Control", "no-store"), new byte[0]);
    }

    private RouteResponse json(int status, Object payload) {
        return new RouteResponse(status, Map.of("Content-Type", "application/json; charset=utf-8",
                "Cache-Control", "no-store"), json.writeValueAsBytes(payload));
    }
}
