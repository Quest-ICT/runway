package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** MaintenanceSiteFilter: 503s the public site in maintenance while exempting the app + tenant admin realms. */
class MaintenanceSiteFilterTest {

    private final SiteResolver resolver = mock(SiteResolver.class);
    private final MaintenanceSiteFilter filter = new MaintenanceSiteFilter(resolver);

    private static Site site(boolean maintenance, String message, SiteStatus status) {
        return new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                status, maintenance, message);
    }

    /** Runs the filter for {@code uri} with the given bound site; returns the response and the chain mock. */
    private FilterChain runFor(String uri, Optional<Site> current, MockHttpServletResponse response) throws Exception {
        when(resolver.current()).thenReturn(current);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        return chain;
    }

    @Test
    void aPublicRequestToAMaintenanceSiteGets503WithTheMessage() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = runFor("/about", Optional.of(site(true, "Back at 3pm", SiteStatus.ACTIVE)), res);
        assertEquals(503, res.getStatus());
        assertTrue(res.getContentAsString().contains("Back at 3pm"), "the tenant message is shown");
        verify(chain, never()).doFilter(any(), any()); // short-circuited
    }

    @Test
    void theMaintenancePageEscapesTheTenantMessage() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        runFor("/about", Optional.of(site(true, "<script>x</script>", SiteStatus.ACTIVE)), res);
        assertTrue(res.getContentAsString().contains("&lt;script&gt;"), "message must be HTML-escaped");
        assertTrue(!res.getContentAsString().contains("<script>x</script>"), "raw script must not appear");
    }

    @Test
    void theTenantAdminRealmIsExemptSoTheManagerCanTurnItOff() throws Exception {
        Optional<Site> maint = Optional.of(site(true, null, SiteStatus.ACTIVE));
        for (String uri : List.of("/__admin", "/__admin/maintenance", "/login", "/logout", "/__assets/runway.svg")) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = runFor(uri, maint, res);
            verify(chain).doFilter(any(), any());
            assertEquals(200, res.getStatus(), uri + " must pass through");
        }
    }

    @Test
    void theApplicationRealmIsExempt() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = runFor("/__app/backups", Optional.of(site(true, null, SiteStatus.ACTIVE)), res);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aSiteNotInMaintenancePassesThrough() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = runFor("/about", Optional.of(site(false, null, SiteStatus.ACTIVE)), res);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aSuspendedSiteIsLeftToTheSuspendedFilter() throws Exception {
        // maintenance + suspended: this filter must NOT act (SuspendedSiteFilter already 503'd upstream).
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = runFor("/about", Optional.of(site(true, "msg", SiteStatus.SUSPENDED)), res);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anUnconfiguredHostWithNoSitePassesThrough() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = runFor("/about", Optional.empty(), res);
        verify(chain).doFilter(any(), any());
    }
}
