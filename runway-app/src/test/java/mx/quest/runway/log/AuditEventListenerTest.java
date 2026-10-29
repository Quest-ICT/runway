package mx.quest.runway.log;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.AfterEach;

/** The audit listener records tenant login outcomes with the client address, and skips app-realm logins. */
class AuditEventListenerTest {

    private static Site site() {
        return new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE);
    }

    @Test
    void aTenantLoginSuccessIsAudited() {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.current()).thenReturn(Optional.of(site()));
        SiteLogService logs = mock(SiteLogService.class);

        new AuditEventListener(resolver, logs)
                .onSuccess(new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken("bob", "x")));

        verify(logs).audit("acme", "bob", "login.success", "");
    }

    @Test
    void aFailedTenantLoginIsAudited() {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.current()).thenReturn(Optional.of(site()));
        SiteLogService logs = mock(SiteLogService.class);

        new AuditEventListener(resolver, logs).onFailure(new AuthenticationFailureBadCredentialsEvent(
                new UsernamePasswordAuthenticationToken("mallory", "x"), new BadCredentialsException("bad")));

        verify(logs).audit("acme", "mallory", "login.failure", "");
    }

    @Test
    void anAppRealmLoginWithNoBoundSiteIsNotAudited() {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.current()).thenReturn(Optional.empty());
        SiteLogService logs = mock(SiteLogService.class);

        new AuditEventListener(resolver, logs)
                .onSuccess(new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken("root", "x")));

        verify(logs, never()).audit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void onPath(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void aTenantLogoutIsAudited() {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.current()).thenReturn(Optional.of(site()));
        SiteLogService logs = mock(SiteLogService.class);
        onPath("/logout");

        new AuditEventListener(resolver, logs)
                .onLogout(new LogoutSuccessEvent(new UsernamePasswordAuthenticationToken("bob", "x")));

        verify(logs).audit("acme", "bob", "logout", "ip=127.0.0.1");
    }

    @Test
    void aLoginRecordsTheResolvedClientAddressNotTheProxy() {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.current()).thenReturn(Optional.of(site()));
        SiteLogService logs = mock(SiteLogService.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("198.51.100.7"); // what TrustedForwardedHeaderFilter left after reading the proxy's XFF
        request.addHeader("X-Forwarded-For", "203.0.113.66"); // never read here
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuditEventListener listener = new AuditEventListener(resolver, logs);

        listener.onSuccess(new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken("bob", "x")));
        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(
                new UsernamePasswordAuthenticationToken("mallory", "x"), new BadCredentialsException("bad")));

        verify(logs).audit("acme", "bob", "login.success", "ip=198.51.100.7");
        verify(logs).audit("acme", "mallory", "login.failure", "ip=198.51.100.7");
    }

    @Test
    void anAppRealmLoginOrLogoutOnASiteHostNeverLandsInTheSitesJournal() {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.current()).thenReturn(Optional.of(site())); // the app console reached on a site's host
        SiteLogService logs = mock(SiteLogService.class);
        AuditEventListener listener = new AuditEventListener(resolver, logs);

        onPath("/app/login");
        listener.onSuccess(new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken("root", "x")));
        onPath("/app/logout");
        listener.onLogout(new LogoutSuccessEvent(new UsernamePasswordAuthenticationToken("root", "x")));

        verify(logs, never()).audit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
