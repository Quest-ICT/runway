package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;

class LocaleNegotiatorTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final Site SITE =
            new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN, ES), null, SiteStatus.ACTIVE);

    private static Page page(Locale... locales) {
        return new Page("p", "/p", Map.of(), List.of(locales), Map.of(), Map.of(), "", "_master", false, false, null, null);
    }

    @Test
    void cookieWinsWhenAvailable() {
        assertEquals(ES, LocaleNegotiator.negotiate(SITE, page(EN, ES), "es", List.of(EN)));
    }

    @Test
    void acceptLanguageWhenNoCookie() {
        assertEquals(ES, LocaleNegotiator.negotiate(SITE, page(EN, ES), null, List.of(ES, EN)));
    }

    @Test
    void siteDefaultWhenNothingElseMatches() {
        assertEquals(EN, LocaleNegotiator.negotiate(SITE, page(EN, ES), null, List.of()));
    }

    @Test
    void unsupportedCookieFallsThrough() {
        assertEquals(EN, LocaleNegotiator.negotiate(SITE, page(EN, ES), "fr", List.of()));
    }

    @Test
    void unavailableLocaleSkipped() {
        // page only in en; an es cookie/accept-language must not be chosen
        assertEquals(EN, LocaleNegotiator.negotiate(SITE, page(EN), "es", List.of(ES)));
    }

    @Test
    void fallsBackToPagePrimaryWhenDefaultUnavailable() {
        // page only in es (site default en not available) → primary is es
        assertEquals(ES, LocaleNegotiator.negotiate(SITE, page(ES), null, List.of()));
    }
}
