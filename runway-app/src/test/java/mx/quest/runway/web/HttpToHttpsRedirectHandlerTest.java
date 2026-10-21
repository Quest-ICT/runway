package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** The plain-port behaviour: redirect (hardened) + ACME webroot exemption; other connectors untouched. */
class HttpToHttpsRedirectHandlerTest {

    private static final String CONNECTOR = "runway-http-redirect";

    @TempDir
    Path acmeDir;

    private Response response;
    private HttpFields.Mutable responseHeaders;
    private Callback callback;
    private boolean innerHandled;

    private HttpToHttpsRedirectHandler handler(int httpsPort, int status) {
        Handler inner = new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                innerHandled = true;
                return true;
            }
        };
        return new HttpToHttpsRedirectHandler(inner, CONNECTOR, () -> httpsPort, status, acmeDir);
    }

    @BeforeEach
    void setUp() {
        response = mock(Response.class);
        responseHeaders = HttpFields.build();
        when(response.getHeaders()).thenReturn(responseHeaders);
        callback = mock(Callback.class);
        innerHandled = false;
    }

    private Request request(String connector, String host, String path, String query) {
        Request request = mock(Request.class, RETURNS_DEEP_STUBS);
        when(request.getConnectionMetaData().getConnector().getName()).thenReturn(connector);
        HttpURI uri = mock(HttpURI.class);
        when(uri.getCanonicalPath()).thenReturn(path);
        when(uri.getPathQuery()).thenReturn(query == null ? path : path + "?" + query);
        when(request.getHttpURI()).thenReturn(uri);
        HttpFields.Mutable headers = HttpFields.build();
        if (host != null) {
            headers.put(HttpHeader.HOST, host);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    void anotherConnectorsTrafficFallsThroughToTheApplication() throws Exception {
        assertTrue(handler(443, 301).handle(request("main-https", "x.test", "/page", null), response, callback));
        assertTrue(innerHandled, "the wrapped application handler got the request");
    }

    @Test
    void aPlainRequestRedirectsToHttpsPreservingPathAndQuery() throws Exception {
        handler(443, 301).handle(request(CONNECTOR, "acme.test:80", "/a/b", "q=1"), response, callback);
        assertFalse(innerHandled, "plain-port traffic never reaches the application");
        verify(response).setStatus(301);
        assertEquals("https://acme.test/a/b?q=1", responseHeaders.get(HttpHeader.LOCATION),
                "host port stripped, 443 omitted, path+query preserved");
        verify(callback).succeeded();
    }

    @Test
    void aNonDefaultHttpsPortAppearsInTheLocation() throws Exception {
        handler(8443, 308).handle(request(CONNECTOR, "acme.test", "/", null), response, callback);
        verify(response).setStatus(308);
        assertEquals("https://acme.test:8443/", responseHeaders.get(HttpHeader.LOCATION));
    }

    @Test
    void anIpv6HostKeepsItsBrackets() throws Exception {
        handler(443, 301).handle(request(CONNECTOR, "[::1]:8080", "/x", null), response, callback);
        assertEquals("https://[::1]/x", responseHeaders.get(HttpHeader.LOCATION));
    }

    @Test
    void aMissingHostHeaderIsA400() throws Exception {
        handler(443, 301).handle(request(CONNECTOR, null, "/", null), response, callback);
        verify(response).setStatus(400);
        verify(callback).succeeded();
    }

    @Test
    void controlCharactersInThePathAreRejected() throws Exception {
        handler(443, 301).handle(request(CONNECTOR, "acme.test", "/x\r\nInjected: yes", null), response, callback);
        verify(response).setStatus(400); // header-injection guard
    }

    @Test
    void anAcmeChallengeIsServedAsPlainTextNotRedirected() throws Exception {
        Files.writeString(acmeDir.resolve("tok-123_abc"), "CHALLENGE-BODY", StandardCharsets.UTF_8);
        handler(443, 301).handle(
                request(CONNECTOR, "acme.test", "/.well-known/acme-challenge/tok-123_abc", null), response, callback);
        verify(response).setStatus(200);
        ArgumentCaptor<ByteBuffer> body = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(response).write(eq(true), body.capture(), any());
        assertEquals("CHALLENGE-BODY", StandardCharsets.UTF_8.decode(body.getValue()).toString());
        assertEquals("no-store", responseHeaders.get(HttpHeader.CACHE_CONTROL));
    }

    @Test
    void aMissingChallengeTokenIsA404() throws Exception {
        handler(443, 301).handle(
                request(CONNECTOR, "acme.test", "/.well-known/acme-challenge/absent", null), response, callback);
        verify(response).setStatus(404);
    }

    @Test
    void aTraversalShapedTokenIsA404NeverServed(@TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "PRIVATE", StandardCharsets.UTF_8);
        // Tokens with separators/dots fail the shape check; nothing outside the webroot is reachable.
        for (String evil : new String[] {"../secret.txt", "..%2Fsecret.txt", "a/b", "..", ".hidden"}) {
            handler(443, 301).handle(
                    request(CONNECTOR, "acme.test", "/.well-known/acme-challenge/" + evil, null), response, callback);
        }
        verify(response, org.mockito.Mockito.times(5)).setStatus(404);
    }

    @Test
    void aSymlinkInsideTheWebrootIsRefused(@TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "PRIVATE", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(acmeDir.resolve("innocent-token"), outside.resolve("secret.txt"));
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return; // filesystem without symlink support — nothing to prove here
        }
        handler(443, 301).handle(
                request(CONNECTOR, "acme.test", "/.well-known/acme-challenge/innocent-token", null),
                response, callback);
        verify(response).setStatus(404); // a symlink can smuggle a file out of the webroot — never follow it
    }

    @Test
    void anOversizedChallengeFileIsRefused() throws Exception {
        Files.write(acmeDir.resolve("huge-token"), new byte[65 * 1024]); // above the 64 KiB cap
        handler(443, 301).handle(
                request(CONNECTOR, "acme.test", "/.well-known/acme-challenge/huge-token", null), response, callback);
        verify(response).setStatus(404); // real key authorizations are ~100 bytes
    }

    @Test
    void aDegenerateHostThatStripsToNothingIsA400() throws Exception {
        handler(443, 301).handle(request(CONNECTOR, ":8080", "/x", null), response, callback);
        verify(response).setStatus(400); // never emit a malformed https://:port Location
    }
}
