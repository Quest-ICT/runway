package mx.quest.runway.log;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The app-realm listener records sign-in and sign-out only for the app's own paths, never a tenant's. */
class AppAuditEventListenerTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void onPath(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("203.0.113.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void anAppLogoutIsRecordedWithTheClientIp() {
        AppActivityLog activity = mock(AppActivityLog.class);
        onPath("/app/logout");
        new AppAuditEventListener(activity)
                .onLogout(new LogoutSuccessEvent(new UsernamePasswordAuthenticationToken("root", "x")));
        verify(activity).record("root", "logout", "ip=203.0.113.9");
    }

    @Test
    void aTenantLogoutOrLoginIsNotRecordedInTheAppJournal() {
        AppActivityLog activity = mock(AppActivityLog.class);
        AppAuditEventListener listener = new AppAuditEventListener(activity);
        onPath("/logout");
        listener.onLogout(new LogoutSuccessEvent(new UsernamePasswordAuthenticationToken("bob", "x")));
        onPath("/login");
        listener.onSuccess(new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken("bob", "x")));
        verify(activity, never()).record(anyString(), anyString(), anyString());
    }

    @Test
    void aLogoutOutsideARequestIsIgnored() {
        AppActivityLog activity = mock(AppActivityLog.class);
        new AppAuditEventListener(activity)
                .onLogout(new LogoutSuccessEvent(new UsernamePasswordAuthenticationToken("root", "x")));
        verify(activity, never()).record(anyString(), anyString(), anyString());
    }
}
