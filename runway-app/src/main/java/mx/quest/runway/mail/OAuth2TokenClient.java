package mx.quest.runway.mail;

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gets the access token the platform mailer signs in to SMTP with when the account uses OAuth2 (SASL
 * {@code XOAUTH2}): an OAuth2 <b>client-credentials</b> grant (RFC 6749 §4.4) against the configured token endpoint,
 * which is how Microsoft 365 lets an application send through Exchange Online once password SMTP is retired.
 *
 * <p><b>Caching.</b> A token is reused until shortly before it expires (five minutes, or half its lifetime when that
 * is shorter), so a burst of recovery mails costs one token request, not one each. One entry is kept, for the request
 * last made: there is one platform account. Changing any part of the request (endpoint, client, secret, scope) asks
 * for a new token. {@link #forget()} drops the entry, for when the mail server refused the token.
 *
 * <p><b>Bounds.</b> The endpoint must be {@code https} (plain {@code http} only to a loopback address, for a local
 * identity provider or a test); the connection and the whole exchange, body included, are time-limited, and the
 * response is read up to {@value #MAX_RESPONSE_BYTES} bytes. No redirect is followed: a token endpoint that redirects is misconfigured,
 * and following it would send the client secret wherever the redirect points.
 *
 * <p><b>Secrets.</b> The client secret goes only into the form body of the token request. Neither it nor a token is
 * logged or put in an exception message; an error names the HTTP status and the provider's own {@code error} and
 * {@code error_description} (for Microsoft, the {@code AADSTS…} code that says what to fix).
 *
 * <p>Thread-safe. Concurrent callers for the same request share one in-flight token request and wait at most until
 * its deadline, so a stalled identity provider holds every sender for one deadline, not one each in turn.
 */
@Component
public class OAuth2TokenClient {

    /** The longest token response read. Real ones are a few kilobytes. */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** How long to wait for the token endpoint to accept the connection. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** How long the whole token exchange may take. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** How long before expiry a cached token is replaced, at most. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    /** The lifetime assumed when the response gives none: short, so a wrong guess costs little. */
    static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_TEXT = 300;

    private final HttpClient http;
    private final Clock clock;
    private final Duration deadline;
    private Cached cached;
    /** The token request under way, which callers for the same request join. Guarded by {@code this}. */
    private InFlight inFlight;

    private record InFlight(Request request, CompletableFuture<String> result, long deadlineNanos) {
    }

    @Autowired
    public OAuth2TokenClient() {
        this(httpClient(), Clock.systemUTC());
    }

    /** The client the application uses: bounded connect, and never a redirect. Package-visible for tests. */
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    OAuth2TokenClient(HttpClient http, Clock clock) {
        this(http, clock, REQUEST_TIMEOUT);
    }

    /** With a shorter deadline for the whole exchange, so a test of a stalled server finishes quickly. */
    OAuth2TokenClient(HttpClient http, Clock clock, Duration deadline) {
        this.http = http;
        this.clock = clock;
        this.deadline = deadline;
    }

    /**
     * What to ask the token endpoint for.
     *
     * @param tokenUrl     the token endpoint
     * @param clientId     the client (application) id
     * @param clientSecret the client secret, in clear
     * @param scope        the scope to request; blank sends none
     */
    public record Request(String tokenUrl, String clientId, String clientSecret, String scope) {

        /** Never leak the secret in diagnostics. */
        @Override
        public String toString() {
            return "Request[tokenUrl=" + tokenUrl + ", clientId=" + clientId + ", scope=" + scope + "]";
        }
    }

    private record Cached(Request request, String token, Instant renewAt) {
    }

    /**
     * No token: the OAuth2 settings are incomplete or not allowed, or the token endpoint could not be reached or
     * refused. A {@link MessagingException}, so every mail caller's handling applies; its own type lets the console
     * say that the token, not the mail server, is what failed.
     */
    public static final class TokenException extends MessagingException {

        TokenException(String message) {
            super(message);
        }

        TokenException(String message, Exception cause) {
            super(message, cause);
        }
    }

    /**
     * An access token for {@code request}: the cached one while it is still fresh, otherwise a new one.
     *
     * @throws TokenException when the request is incomplete, the endpoint is not allowed, cannot be reached, or
     *                            does not return a bearer token; the message says which
     */
    public String token(Request request) throws TokenException {
        CompletableFuture<String> attempt;
        long deadlineNanos;
        boolean mine = false;
        Instant now;
        synchronized (this) {
            now = clock.instant();
            if (cached != null && cached.request().equals(request) && now.isBefore(cached.renewAt())) {
                return cached.token();
            }
            cached = null;
            if (inFlight != null && inFlight.request().equals(request)) {
                attempt = inFlight.result();
                deadlineNanos = inFlight.deadlineNanos();
            } else {
                attempt = new CompletableFuture<>();
                deadlineNanos = System.nanoTime() + deadline.toNanos();
                inFlight = new InFlight(request, attempt, deadlineNanos);
                mine = true;
            }
        }
        if (mine) {
            try {
                String token = fetch(request, now);
                attempt.complete(token);
                return token;
            } catch (TokenException | RuntimeException e) {
                attempt.completeExceptionally(e);
                throw e;
            } finally {
                synchronized (this) {
                    if (inFlight != null && inFlight.result() == attempt) {
                        inFlight = null;
                    }
                }
            }
        }
        // Another caller is already asking: wait for its answer, no longer than its own deadline (plus a moment).
        try {
            long wait = Math.max(0, deadlineNanos - System.nanoTime()) + TimeUnit.SECONDS.toNanos(1);
            return attempt.get(wait, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new TokenException(cause.getMessage(), cause instanceof Exception ex ? ex : e);
        } catch (TimeoutException e) {
            throw new TokenException("OAuth2 token request did not complete within " + deadline.toSeconds()
                    + " seconds", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException("OAuth2 token request was interrupted", e);
        }
    }

    /** One token request, against the deadline; the token is cached on success. */
    private String fetch(Request request, Instant now) throws TokenException {
        URI endpoint = endpoint(request);
        String form = "grant_type=client_credentials"
                + "&client_id=" + encode(request.clientId())
                + "&client_secret=" + encode(request.clientSecret())
                + (blank(request.scope()) ? "" : "&scope=" + encode(request.scope().strip()));
        HttpRequest post = HttpRequest.newBuilder(endpoint)
                .timeout(deadline)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        // The request timeout covers only the wait for the response headers; a server that then sends its body a byte
        // at a time would hold this call, and every mail sender waiting on it, for as long as it liked. So
        // the whole exchange, body included, runs against one deadline, and the body is cut off past the cap.
        CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(post,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
        HttpResponse<byte[]> response;
        try {
            response = exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new TokenException("OAuth2 token request to " + endpoint.getHost() + " did not complete within "
                    + deadline.toSeconds() + " seconds", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io && io.getMessage() != null
                    && io.getMessage().contains("exceeds capacity")) { // BodyHandlers.limiting
                throw new TokenException("OAuth2 token response from " + endpoint.getHost() + " is larger than "
                        + MAX_RESPONSE_BYTES + " bytes");
            }
            throw new TokenException("OAuth2 token request to " + endpoint.getHost() + " failed: " + cause,
                    cause instanceof Exception ex ? ex : e);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new TokenException("OAuth2 token request to " + endpoint.getHost() + " was interrupted", e);
        }
        byte[] body = response.body();
        JsonNode json = parse(body);
        if (response.statusCode() != 200) {
            throw new TokenException("OAuth2 token request to " + endpoint.getHost() + " was refused (HTTP "
                    + response.statusCode() + ")" + providerError(json));
        }
        String token = json == null ? null : text(json, "access_token");
        if (blank(token)) {
            throw new TokenException("OAuth2 token response from " + endpoint.getHost()
                    + " has no access_token" + providerError(json));
        }
        String type = text(json, "token_type");
        if (type != null && !type.equalsIgnoreCase("bearer")) {
            throw new TokenException("OAuth2 token response from " + endpoint.getHost()
                    + " is not a bearer token (token_type " + clip(type) + ")");
        }
        Duration lifetime = lifetime(json);
        Duration margin = lifetime.dividedBy(2).compareTo(REFRESH_MARGIN) < 0 ? lifetime.dividedBy(2) : REFRESH_MARGIN;
        synchronized (this) {
            cached = new Cached(request, token, now.plus(lifetime).minus(margin));
        }
        return token;
    }

    /** Drops the cached token, so the next send asks for a new one (after the mail server refused it). */
    public synchronized void forget() {
        cached = null;
    }

    /**
     * The token endpoint, checked: complete request, an absolute {@code https} URL, or {@code http} to a loopback
     * address. Public so the settings screen can refuse a bad endpoint when it is saved, not at the first send.
     *
     * @throws TokenException naming what is wrong
     */
    public static URI endpoint(Request request) throws TokenException {
        if (blank(request.tokenUrl()) || blank(request.clientId()) || blank(request.clientSecret())) {
            throw new TokenException("OAuth2 sign-in needs a token endpoint, a client ID and a client secret");
        }
        URI uri;
        try {
            uri = new URI(request.tokenUrl().strip());
        } catch (java.net.URISyntaxException e) {
            throw new TokenException("the OAuth2 token endpoint is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new TokenException("the OAuth2 token endpoint must be a plain https URL with a host");
        }
        if (scheme.equals("https") || (scheme.equals("http") && isLoopback(uri.getHost()))) {
            return uri;
        }
        throw new TokenException("the OAuth2 token endpoint must use https (http is accepted only on this "
                + "machine's loopback address)");
    }

    private static boolean isLoopback(String host) {
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (literal.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            return InetAddress.ofLiteral(literal).isLoopbackAddress(); // a literal only: never resolves DNS
        } catch (IllegalArgumentException notALiteral) {
            return false;
        }
    }

    private static JsonNode parse(byte[] body) {
        try {
            JsonNode json = MAPPER.readTree(body);
            return json != null && json.isObject() ? json : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static Duration lifetime(JsonNode json) {
        JsonNode expires = json.get("expires_in");
        long seconds = -1;
        if (expires != null && expires.isNumber()) {
            seconds = expires.asLong();
        } else if (expires != null && expires.isString()) {
            try {
                seconds = Long.parseLong(expires.asString().strip()); // some providers send it as a string
            } catch (NumberFormatException ignored) {
                seconds = -1;
            }
        }
        return seconds > 0 ? Duration.ofSeconds(Math.min(seconds, Duration.ofDays(1).toSeconds())) : DEFAULT_LIFETIME;
    }

    private static String providerError(JsonNode json) {
        if (json == null) {
            return "";
        }
        String error = text(json, "error");
        String description = text(json, "error_description");
        if (blank(error) && blank(description)) {
            return "";
        }
        return ": " + clip(Objects.requireNonNullElse(error, "error"))
                + (blank(description) ? "" : " — " + clip(description));
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }

    /** One line of provider text, bounded: it ends up in a console message and the activity log. */
    private static String clip(String text) {
        String line = text.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return line.length() > MAX_ERROR_TEXT ? line.substring(0, MAX_ERROR_TEXT) + "…" : line;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
