package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.CsrfToken;

/**
 * The site-scoped tenant repositories store each site's CSRF token / SecurityContext under its <b>own</b>
 * session attribute ({@code <base>::<siteId>}) — so two sites sharing ONE session (the shared-cookie-domain
 * topology) never collide: independent CSRF tokens and independent, simultaneous logins.
 */
class SiteScopedRepositoriesTest {

    private static final String CSRF_BASE = "RUNWAY_SITE_CSRF_TOKEN";
    private static final String CTX_BASE = "RUNWAY_SITE_SECURITY_CONTEXT";

    private static Site site(String id) {
        return new Site(id, id, List.of(id + ".test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE);
    }

    /** A request bound to {@code shared} whose host resolves (via the mock) to the given site. */
    private static MockHttpServletRequest requestFor(MockHttpSession shared) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/login");
        r.setSession(shared);
        return r;
    }

    @Test
    void csrfTokensAreKeptPerSiteInOneSharedSession() {
        SiteResolver resolver = mock(SiteResolver.class);
        SiteScopedCsrfTokenRepository repo = new SiteScopedCsrfTokenRepository(resolver, CSRF_BASE);
        MockHttpSession shared = new MockHttpSession();

        // Site alpha writes its token.
        when(resolver.current()).thenReturn(Optional.of(site("alpha")));
        MockHttpServletRequest a = requestFor(shared);
        CsrfToken tokenA = repo.generateToken(a);
        repo.saveToken(tokenA, a, new MockHttpServletResponse());

        // Site beta writes its token — same session, must NOT touch alpha's.
        when(resolver.current()).thenReturn(Optional.of(site("beta")));
        MockHttpServletRequest b = requestFor(shared);
        CsrfToken tokenB = repo.generateToken(b);
        repo.saveToken(tokenB, b, new MockHttpServletResponse());

        CsrfToken storedA = (CsrfToken) shared.getAttribute(CSRF_BASE + "::alpha");
        CsrfToken storedB = (CsrfToken) shared.getAttribute(CSRF_BASE + "::beta");
        assertNotNull(storedA, "alpha's CSRF token lives under its own attribute");
        assertNotNull(storedB, "beta's CSRF token lives under its own attribute");
        assertNotEquals(storedA.getToken(), storedB.getToken(), "the two sites hold independent CSRF tokens");
        assertNull(shared.getAttribute(CSRF_BASE), "the un-suffixed base attribute is never used");

        // Loading for alpha still returns alpha's token (beta's write didn't overwrite it).
        when(resolver.current()).thenReturn(Optional.of(site("alpha")));
        assertNotEquals(repo.loadToken(requestFor(shared)).getToken(), storedB.getToken());
    }

    @Test
    void securityContextsAreKeptPerSiteSoLoginsDoNotOverwrite() {
        SiteResolver resolver = mock(SiteResolver.class);
        SiteScopedSecurityContextRepository repo = new SiteScopedSecurityContextRepository(resolver, CTX_BASE);
        MockHttpSession shared = new MockHttpSession();

        // Simulate an established login per site: a context stored under each site's own attribute.
        SecurityContext ctxAlpha = new SecurityContextImpl(new TestingAuthenticationToken("alpha-admin", "x", "ROLE_ADMIN"));
        SecurityContext ctxBeta = new SecurityContextImpl(new TestingAuthenticationToken("beta-admin", "x", "ROLE_ADMIN"));
        shared.setAttribute(CTX_BASE + "::alpha", ctxAlpha);
        shared.setAttribute(CTX_BASE + "::beta", ctxBeta);

        // A request resolving to alpha sees alpha's principal — and ONLY alpha's.
        when(resolver.current()).thenReturn(Optional.of(site("alpha")));
        MockHttpServletRequest a = requestFor(shared);
        assertTrue(repo.containsContext(a));
        assertNotNull(repo.loadDeferredContext(a).get().getAuthentication());
        assertTrue(repo.loadDeferredContext(a).get().getAuthentication().getName().equals("alpha-admin"));

        // A request resolving to beta sees beta's principal — the two coexist, neither overwrote the other.
        when(resolver.current()).thenReturn(Optional.of(site("beta")));
        MockHttpServletRequest b = requestFor(shared);
        assertTrue(repo.loadDeferredContext(b).get().getAuthentication().getName().equals("beta-admin"));

        // A request resolving to a THIRD site has no context (isolation, not a leak from either).
        when(resolver.current()).thenReturn(Optional.of(site("gamma")));
        assertFalse(repo.containsContext(requestFor(shared)));
    }
}
