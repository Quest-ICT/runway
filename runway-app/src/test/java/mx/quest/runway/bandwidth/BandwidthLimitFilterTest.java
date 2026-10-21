package mx.quest.runway.bandwidth;

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
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** BandwidthLimitFilter: 503s an over-quota site's public traffic while exempting the console/app realms. */
class BandwidthLimitFilterTest {

    private final SiteResolver resolver = mock(SiteResolver.class);
    private final BandwidthService bandwidth = mock(BandwidthService.class);
    private final BandwidthLimitFilter filter = new BandwidthLimitFilter(resolver, bandwidth);

    private static final Site ACME = new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
            List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE);

    private FilterChain runFor(String uri, boolean over, MockHttpServletResponse response) throws Exception {
        when(resolver.current()).thenReturn(Optional.of(ACME));
        when(bandwidth.isOverQuota("acme")).thenReturn(over);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        return chain;
    }

    @Test
    void anOverQuotaSitesPublicTrafficGets503() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = runFor("/about", true, res);
        assertEquals(503, res.getStatus());
        assertTrue(res.getContentAsString().toLowerCase().contains("bandwidth"));
        assertEquals("3600", res.getHeader("Retry-After"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theConsoleAndAppRealmsAreExemptEvenWhenOverQuota() throws Exception {
        for (String uri : List.of("/__admin", "/__admin/bandwidth", "/login", "/__assets/runway.svg", "/__app/x")) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = runFor(uri, true, res);
            verify(chain).doFilter(any(), any());
            assertEquals(200, res.getStatus(), uri + " must pass through");
        }
    }

    @Test
    void aSiteUnderQuotaPassesThrough() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = runFor("/about", false, res);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anUnconfiguredHostWithNoSitePassesThrough() throws Exception {
        when(resolver.current()).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        request.setRequestURI("/about");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, res, chain);
        verify(chain).doFilter(any(), any());
    }
}
