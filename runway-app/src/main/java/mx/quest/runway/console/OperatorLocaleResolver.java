package mx.quest.runway.console;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.web.RequestPaths;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The DispatcherServlet's {@code localeResolver}, made <b>realm-aware</b> so the operator console gets a
 * switchable, remembered UI language WITHOUT disturbing the public tenant sites.
 *
 * <ul>
 *   <li><b>Operator requests</b> ({@link OperatorPaths#isOperatorRequest} — the {@code /__app} and
 *       {@code /__admin} consoles, their logins, and the {@code /__ui-lang} switch endpoint): the language is a
 *       cookie ({@value #COOKIE}) the operator sets from the topbar switcher, falling back to the best offered
 *       {@code Accept-Language} match, then the configured default. Only offered languages are ever returned.</li>
 *   <li><b>Every other request</b> (the public per-tenant sites): behaves exactly like the default
 *       Accept-Language resolver ({@code request.getLocale()}). The tenant sites run their OWN per-site locale
 *       negotiation downstream; this resolver deliberately does not touch it, keeping the two realms isolated.</li>
 * </ul>
 *
 * <p>Registered under the bean name {@code localeResolver} (the name the DispatcherServlet looks up), which
 * displaces Spring Boot's default {@code AcceptHeaderLocaleResolver}. Being cookie-backed (no HTTP session
 * attribute), the operator language never collides across the two realms sharing one session.
 */
public class OperatorLocaleResolver implements LocaleResolver {

    /** The cookie the operator console remembers its chosen UI language in — never shared with any per-site cookie. */
    static final String COOKIE = "runway-op-lang";

    private static final int ONE_YEAR_SECONDS = 365 * 24 * 60 * 60;

    private final List<Locale> supported;
    private final Locale fallback;

    /**
     * @param uiLanguages    the offered BCP-47 language codes (order irrelevant here); an empty list disables
     *                       switching (only the fallback and Accept-Language remain, matching nothing offered)
     * @param defaultLanguage the fallback language code when the browser requests none offered
     */
    public OperatorLocaleResolver(List<String> uiLanguages, String defaultLanguage) {
        this.supported = uiLanguages.stream().map(Locale::forLanguageTag).toList();
        this.fallback = Locale.forLanguageTag(defaultLanguage);
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        if (!OperatorPaths.isOperatorRequest(RequestPaths.normalized(request))) {
            // Public tenant-site request: preserve the default Accept-Language behavior untouched.
            return request.getLocale();
        }
        Locale fromCookie = fromCookie(request);
        if (fromCookie != null) {
            return fromCookie;
        }
        Locale fromAccept = bestAccept(request);
        return fromAccept != null ? fromAccept : fallback;
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        Cookie cookie = new Cookie(COOKIE, locale == null ? "" : locale.toLanguageTag());
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(locale == null ? 0 : ONE_YEAR_SECONDS);
        response.addCookie(cookie);
    }

    /** The offered locale named by the cookie, or {@code null} if there is no cookie or it names an unoffered one. */
    private Locale fromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                return match(Locale.forLanguageTag(c.getValue()));
            }
        }
        return null;
    }

    /** The best offered locale by browser preference order, or {@code null} if none is offered. */
    private Locale bestAccept(HttpServletRequest request) {
        Enumeration<Locale> requested = request.getLocales();
        while (requested.hasMoreElements()) {
            Locale hit = match(requested.nextElement());
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** The offered locale whose language equals {@code candidate}'s, or {@code null} — so only offered ones return. */
    private Locale match(Locale candidate) {
        if (candidate == null) {
            return null;
        }
        for (Locale s : supported) {
            if (s.getLanguage().equals(candidate.getLanguage())) {
                return s;
            }
        }
        return null;
    }
}
