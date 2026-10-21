package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.RequestDispatcher;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** The error controller: console/reserved paths get the self-contained styled page; public pages delegate to ErrorPages. */
class RunwayErrorControllerTest {

    private static MockHttpServletRequest errorRequest(int status, String originalUri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        return request;
    }

    @Test
    void aConsoleReservedPathGetsTheStyledConsoleErrorNotTheTenantPage() {
        SiteResolver siteResolver = mock(SiteResolver.class);
        ErrorPages errorPages = mock(ErrorPages.class);
        RunwayErrorController controller = new RunwayErrorController(siteResolver, errorPages);

        ResponseEntity<String> response = controller.handle(errorRequest(500, "/__admin/icons"));

        assertEquals(500, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Internal Server Error"), "shows the reason phrase");
        assertTrue(response.getBody().contains("Back to the console"), "the self-contained console error page");
        assertTrue(response.getBody().contains("Back to the console</a>"), "styled, not whitelabel");
        verifyNoInteractions(errorPages); // the console branch never touches the tenant error pages
    }

    @Test
    void anAppRealmPathAlsoGetsTheConsoleError() {
        RunwayErrorController controller = new RunwayErrorController(mock(SiteResolver.class), mock(ErrorPages.class));
        ResponseEntity<String> response = controller.handle(errorRequest(404, "/__app/plugins"));
        assertEquals(404, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Not Found"));
    }

    @Test
    void aPublicTenantPathDelegatesToTheSitesOwnErrorPage() {
        SiteResolver siteResolver = mock(SiteResolver.class);
        ErrorPages errorPages = mock(ErrorPages.class);
        Site site = new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE);
        when(siteResolver.resolve(any())).thenReturn(Optional.of(site));
        when(errorPages.render(eq(site), any(Locale.class), eq(HttpStatus.NOT_FOUND)))
                .thenReturn(ResponseEntity.status(404).body("SITE-OWN-404"));
        RunwayErrorController controller = new RunwayErrorController(siteResolver, errorPages);

        MockHttpServletRequest request = errorRequest(404, "/some/public/page");
        request.setServerName("acme.test");
        ResponseEntity<String> response = controller.handle(request);

        assertEquals("SITE-OWN-404", response.getBody(), "delegated to the site's own error page");
    }
}
