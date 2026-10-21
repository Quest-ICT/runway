package mx.quest.runway.reload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The three gates, unit-tested — in particular the loopback check, which an end-to-end test running on localhost
 * structurally cannot exercise (every request it makes IS loopback).
 */
class SiteReloadFilterTest {

    private static final String TOKEN = "s3cret";

    private static ReloadProperties armed() {
        ReloadProperties props = new ReloadProperties();
        props.setToken(TOKEN);
        return props;
    }

    private static MockHttpServletRequest request(String remoteAddr, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/__reload/site/acme");
        req.setRequestURI("/__reload/site/acme");
        req.setRemoteAddr(remoteAddr);
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        return req;
    }

    private static MockHttpServletResponse run(SiteReloadService service, ReloadProperties props,
                                               MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new SiteReloadFilter(service, props).doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void aNonLoopbackPeerGets404AndNeverReachesTheService() throws Exception {
        SiteReloadService service = mock(SiteReloadService.class);
        MockHttpServletResponse res = run(service, armed(), request("203.0.113.7", TOKEN));

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString()).doesNotContain("acme");
        verify(service, never()).reload(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void loopbackV4AndV6BothPass() throws Exception {
        SiteReloadService service = mock(SiteReloadService.class);
        when(service.reload("acme")).thenReturn(true);

        assertThat(run(service, armed(), request("127.0.0.1", TOKEN)).getStatus()).isEqualTo(200);
        assertThat(run(service, armed(), request("::1", TOKEN)).getStatus()).isEqualTo(200);
    }

    @Test
    void aBlankTokenMakesTheEndpointAbsentEvenFromLoopback() throws Exception {
        SiteReloadService service = mock(SiteReloadService.class);
        MockHttpServletResponse res = run(service, new ReloadProperties(), request("127.0.0.1", TOKEN));

        assertThat(res.getStatus()).as("unconfigured means ABSENT, not merely locked").isEqualTo(404);
        verify(service, never()).reload(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aWrongTokenIsRefusedLoudlyBecauseThisEndpointMutates() throws Exception {
        SiteReloadService service = mock(SiteReloadService.class);
        MockHttpServletResponse res = run(service, armed(), request("127.0.0.1", "not-the-token"));

        assertThat(res.getStatus()).isEqualTo(403);
        verify(service, never()).reload(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aMalformedSiteIdNeverReachesTheStore() throws Exception {
        SiteReloadService service = mock(SiteReloadService.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/__reload/site/..%2fetc");
        req.setRequestURI("/__reload/site/../etc");
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("Authorization", "Bearer " + TOKEN);

        assertThat(run(service, armed(), req).getStatus()).isEqualTo(404);
        verify(service, never()).reload(org.mockito.ArgumentMatchers.anyString());
    }
}
