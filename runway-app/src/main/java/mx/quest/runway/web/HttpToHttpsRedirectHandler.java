package mx.quest.runway.web;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plain-HTTP listener's behaviour: 301/308-redirect everything to the HTTPS listener, <b>except</b>
 * {@code /.well-known/acme-challenge/<token>}, which is served as a plain file from the ACME webroot so
 * Let's Encrypt HTTP-01 issuance/renewal keeps working with the redirect on.
 *
 * <p>A Jetty {@link Handler.Wrapper} rather than a servlet filter — it intercepts only requests that arrived
 * on the named redirect connector, <b>before</b> the servlet/filter pipeline entirely (nothing on the plain
 * port ever reaches the application), and lets every other connector's requests fall through to the wrapped
 * application handler untouched. This keeps the redirect out of the kernel's ordered filter ladder by
 * construction.
 *
 * <p>Hardening: ACME tokens are shape-validated ({@code [A-Za-z0-9_-]{1,128}}) and the resolved file is
 * re-confined to the webroot (defense in depth against traversal); the {@code Host} header and path are
 * rejected with {@code 400} if they carry control characters (header-injection guard);
 * IPv6 host literals keep their brackets when the port is stripped.
 */
public class HttpToHttpsRedirectHandler extends Handler.Wrapper {

    private static final Logger log = LoggerFactory.getLogger(HttpToHttpsRedirectHandler.class);
    private static final String ACME_PREFIX = "/.well-known/acme-challenge/";
    private static final Pattern ACME_TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final String connectorName;
    private final IntSupplier httpsPort;
    private final int redirectStatus;
    private final Path acmeDir;

    /**
     * @param handler        the wrapped application handler (all other connectors fall through to it)
     * @param connectorName  the redirect connector's name — only its requests are intercepted
     * @param httpsPort      resolves the HTTPS port to redirect to, queried per request so the <em>actual
     *                       bound</em> port is used (omitted from the Location when 443)
     * @param redirectStatus 301 or 308
     * @param acmeDir        the ACME challenge webroot (created lazily by the ACME client, may be absent)
     */
    public HttpToHttpsRedirectHandler(Handler handler, String connectorName, IntSupplier httpsPort,
                                      int redirectStatus, Path acmeDir) {
        super(handler);
        this.connectorName = Objects.requireNonNull(connectorName, "connectorName");
        this.httpsPort = Objects.requireNonNull(httpsPort, "httpsPort");
        this.redirectStatus = redirectStatus;
        this.acmeDir = acmeDir.toAbsolutePath().normalize();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (!connectorName.equals(request.getConnectionMetaData().getConnector().getName())) {
            return super.handle(request, response, callback); // another connector — the application's traffic
        }
        HttpURI uri = request.getHttpURI();
        String path = uri.getCanonicalPath();
        if (path != null && path.startsWith(ACME_PREFIX)) {
            serveAcmeChallenge(path.substring(ACME_PREFIX.length()), response, callback);
        } else {
            redirect(request, uri, response, callback);
        }
        return true; // the plain port never falls through to the application
    }

    /** ACME HTTP-01 key authorizations are ~100 bytes; anything near this cap is not a challenge file. */
    private static final long MAX_CHALLENGE_BYTES = 64 * 1024;

    /** Serves one challenge file from the webroot; anything suspicious or absent is a plain 404. */
    private void serveAcmeChallenge(String token, Response response, Callback callback) {
        if (!ACME_TOKEN.matcher(token).matches()) {
            finish(response, callback, 404);
            return;
        }
        Path file = acmeDir.resolve(token).normalize();
        // Defense in depth beyond the regex: stay confined to the webroot, refuse symlinks (a link inside the
        // webroot could otherwise smuggle out an arbitrary file), and cap the size (this port is open to anyone).
        if (!file.startsWith(acmeDir) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
            finish(response, callback, 404);
            return;
        }
        try {
            if (Files.size(file) > MAX_CHALLENGE_BYTES) {
                log.warn("refusing over-sized ACME challenge file '{}' ({} bytes)", token, Files.size(file));
                finish(response, callback, 404);
                return;
            }
            byte[] body = Files.readAllBytes(file);
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8");
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            response.write(true, ByteBuffer.wrap(body), callback);
        } catch (Exception e) {
            log.warn("failed to serve ACME challenge '{}'", token, e);
            finish(response, callback, 500);
        }
    }

    private void redirect(Request request, HttpURI uri, Response response, Callback callback) {
        String hostHeader = request.getHeaders().get(HttpHeader.HOST);
        if (hostHeader == null || hostHeader.isBlank()) {
            finish(response, callback, 400);
            return;
        }
        String host = stripPort(hostHeader);
        String pathQuery = uri.getPathQuery() == null || uri.getPathQuery().isEmpty() ? "/" : uri.getPathQuery();
        if (host.isBlank() || hasControlChars(host) || hasControlChars(pathQuery)) {
            finish(response, callback, 400); // degenerate host (e.g. ":8080") or header-injection attempt
            return;
        }
        int port = httpsPort.getAsInt();
        String location = "https://" + host + (port == 443 ? "" : ":" + port) + pathQuery;
        response.setStatus(redirectStatus);
        response.getHeaders().put(HttpHeader.LOCATION, location);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "public, max-age=3600");
        response.getHeaders().put(HttpHeader.CONNECTION, "close");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, "0");
        callback.succeeded();
    }

    /** A bodyless terminal response (the plain port owes nobody an error page). */
    private static void finish(Response response, Callback callback, int status) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, "0");
        callback.succeeded();
    }

    /** Drops a trailing {@code :port}, keeping IPv6 literals (`[::1]:80` → `[::1]`) intact. */
    private static String stripPort(String hostHeader) {
        String host = hostHeader.trim();
        if (host.startsWith("[")) { // IPv6 literal: the port can only follow the closing bracket
            int bracket = host.indexOf(']');
            return bracket >= 0 ? host.substring(0, bracket + 1) : host;
        }
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20 || value.charAt(i) == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
