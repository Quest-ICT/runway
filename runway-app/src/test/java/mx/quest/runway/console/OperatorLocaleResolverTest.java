package mx.quest.runway.console;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The realm-aware operator locale resolver: switchable/remembered for operator requests, and — the isolation
 * guarantee — a pure Accept-Language pass-through for public tenant-site requests (so the per-site locale
 * pipeline is never disturbed).
 */
class OperatorLocaleResolverTest {

    private final OperatorLocaleResolver resolver = new OperatorLocaleResolver(List.of("en", "es"), "en");

    private static MockHttpServletRequest operatorRequest(String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI(uri);
        return r;
    }

    @Test
    void operatorCookieWinsOverAcceptLanguage() {
        MockHttpServletRequest r = operatorRequest("/__app/sites");
        r.addPreferredLocale(Locale.ENGLISH);
        r.setCookies(new Cookie(OperatorLocaleResolver.COOKIE, "es"));
        assertThat(resolver.resolveLocale(r).getLanguage()).isEqualTo("es");
    }

    @Test
    void operatorFallsBackToBestOfferedAcceptLanguage() {
        MockHttpServletRequest r = operatorRequest("/__admin");
        r.addPreferredLocale(Locale.forLanguageTag("es-MX")); // offered by language, not exact tag
        assertThat(resolver.resolveLocale(r).getLanguage()).isEqualTo("es");
    }

    @Test
    void operatorWithUnofferedCookieAndUnofferedAcceptFallsBackToDefault() {
        MockHttpServletRequest r = operatorRequest("/__app");
        r.addPreferredLocale(Locale.FRENCH); // not offered
        r.setCookies(new Cookie(OperatorLocaleResolver.COOKIE, "de")); // not offered
        assertThat(resolver.resolveLocale(r).getLanguage()).isEqualTo("en"); // configured default
    }

    @Test
    void publicRequestIsPureAcceptLanguagePassThrough() {
        // A non-operator (public tenant-site) path: the resolver must NOT constrain to the offered set — it
        // returns exactly request.getLocale(), preserving the default behavior the public pipeline relies on.
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI("/some/public/page");
        r.addPreferredLocale(Locale.FRENCH);
        assertThat(resolver.resolveLocale(r)).isEqualTo(Locale.FRENCH);
        // even the operator cookie is ignored off the operator realm
        r.setCookies(new Cookie(OperatorLocaleResolver.COOKIE, "es"));
        assertThat(resolver.resolveLocale(r)).isEqualTo(Locale.FRENCH);
    }

    @Test
    void setLocaleWritesThenClearsTheCookie() {
        MockHttpServletRequest req = operatorRequest("/__ui-lang");
        MockHttpServletResponse res = new MockHttpServletResponse();
        resolver.setLocale(req, res, Locale.forLanguageTag("es"));
        Cookie set = res.getCookie(OperatorLocaleResolver.COOKIE);
        assertThat(set).isNotNull();
        assertThat(set.getValue()).isEqualTo("es");
        assertThat(set.getPath()).isEqualTo("/");
        assertThat(set.getMaxAge()).isPositive();

        MockHttpServletResponse cleared = new MockHttpServletResponse();
        resolver.setLocale(req, cleared, null);
        assertThat(cleared.getCookie(OperatorLocaleResolver.COOKIE).getMaxAge()).isZero();
    }
}
