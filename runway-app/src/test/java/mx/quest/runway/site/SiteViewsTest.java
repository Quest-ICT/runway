package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

class SiteViewsTest {

    private static final Locale EN = Locale.ENGLISH;

    @Test
    void mapsFieldsAndDerivesBaseUrlFromPrimaryHost() {
        Site site = new Site("acme", "Acme Inc", List.of("acme.test", "www.acme.test"), EN, List.of(EN), null, SiteStatus.ACTIVE);
        SiteView view = SiteViews.toView(site);
        assertEquals("acme", view.id());
        assertEquals("Acme Inc", view.displayName());
        assertEquals(EN, view.defaultLocale());
        assertEquals(List.of(EN), view.locales());
        assertEquals("https://acme.test", view.baseUrl());
    }

    @Test
    void explicitBaseUrlWins() {
        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), "https://acme.example", SiteStatus.ACTIVE);
        assertEquals("https://acme.example", SiteViews.toView(site).baseUrl());
    }

    @Test
    void noHostsLeavesBaseUrlNull() {
        Site site = new Site("acme", "Acme", List.of(), EN, List.of(EN), null, SiteStatus.ACTIVE);
        assertNull(SiteViews.toView(site).baseUrl());
    }
}
