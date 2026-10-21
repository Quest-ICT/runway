package mx.quest.runway.spi.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The immutable input to {@link mx.quest.runway.spi.EndpointContributor#handle}. It is a
 * frozen, host-built view of the request — never the raw servlet objects — so a handler is a
 * pure function of these inputs and stays free of any servlet/Spring type.
 *
 * <p>The {@code site} is the full {@link SiteView} (not just a {@link SiteRef}), so endpoint
 * handlers that build absolute links or enumerate locales (robots, sitemap, feeds) have the
 * site's base URL and locale set directly.
 *
 * <h2>What the host puts in it (since SPI 0.5.0)</h2>
 * <ul>
 *   <li>{@code locale} is negotiated the way a page's is: an explicit {@code ?lang=} in the query, then the
 *       visitor's language cookie for the site, then {@code Accept-Language}, then the site default. It is always
 *       one of the site's locales.</li>
 *   <li>{@code query} holds the URL query string only; the fields of a form body are in {@code form}. Use
 *       {@link #param(String)} to read a field from either.</li>
 *   <li>{@code headers} are keyed by <b>lowercase</b> name ({@code "user-agent"}); lookups ignore case, so
 *       {@code headers().get("User-Agent")} still finds it. Forwarded headers ({@code X-Forwarded-*},
 *       {@code Forwarded}) are never present: the host has already applied them for a trusted proxy and hidden them
 *       from anyone else.</li>
 *   <li>{@code clientIp} is the client address after the host's trusted-proxy gate: the forwarded address when the
 *       connection came from a configured trusted proxy, the connection's own address otherwise. Use it for rate
 *       limits and abuse decisions, never a header.</li>
 *   <li>{@code origin} is the request's {@code scheme://host[:port]} as the trusted-proxy gate leaves it (a trusted
 *       proxy's forwarded scheme and host applied; raw values otherwise), with the default port omitted. Use it to
 *       build absolute URLs when the site has no configured base URL.</li>
 * </ul>
 * A record built with the pre-0.5.0 seven-argument constructor (in a plugin's own tests) has an empty
 * {@code form}, no {@code clientIp} and an empty {@code origin}.
 *
 * <p>All collection and array components are defensively copied; {@link #body()} returns a
 * fresh copy on each call. (Because the body is an array, the record-generated
 * {@code equals}/{@code hashCode} compare it by <em>reference</em>, not content, so they are
 * unreliable for an instance carrying a body — do not rely on record equality for such
 * instances.)
 *
 * @param site     the site the request resolved to (never {@code null})
 * @param locale   the negotiated request locale, one of the site's (never {@code null})
 * @param method   the HTTP method (never {@code null})
 * @param path     the request path (never {@code null})
 * @param query    the URL query parameters, multi-valued (never {@code null}; deeply copied, unmodifiable; may be
 *                 empty)
 * @param headers  request headers by lowercase name, case-insensitive lookup (never {@code null}; copied,
 *                 unmodifiable; may be empty)
 * @param body     the raw request body (never {@code null}; defensively copied; empty when none)
 * @param form     the fields of an {@code application/x-www-form-urlencoded} body, multi-valued (never
 *                 {@code null}; deeply copied, unmodifiable; empty for any other body); since SPI 0.5.0
 * @param clientIp the gated client IP, or {@code null} when unknown; since SPI 0.5.0
 * @param origin   {@code scheme://host[:port]} of the request (never {@code null}; empty when unknown); since SPI
 *                 0.5.0
 */
public record RouteRequest(SiteView site, Locale locale, String method, String path,
                           Map<String, List<String>> query, Map<String, String> headers, byte[] body,
                           Map<String, List<String>> form, String clientIp, String origin) {

    public RouteRequest {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        query = copyParams(query, "query key");
        headers = copyHeaders(headers);
        body = (body == null) ? new byte[0] : body.clone();
        form = copyParams(form, "form key");
        clientIp = (clientIp == null || clientIp.isBlank()) ? null : clientIp;
        origin = (origin == null) ? "" : origin;
    }

    /** The pre-0.5.0 form: no form fields, no client IP, no origin. */
    public RouteRequest(SiteView site, Locale locale, String method, String path,
                        Map<String, List<String>> query, Map<String, String> headers, byte[] body) {
        this(site, locale, method, path, query, headers, body, null, null, null);
    }

    /**
     * The first value of field {@code name}: from the query string, else from the form body.
     *
     * @return the value (possibly empty text); empty when neither carries the field
     * @since SPI 0.5.0
     */
    public Optional<String> param(String name) {
        Objects.requireNonNull(name, "name");
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) {
            values = form.get(name);
        }
        return (values == null || values.isEmpty()) ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    private static Map<String, List<String>> copyParams(Map<String, List<String>> params, String what) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        params.forEach((key, values) -> copy.put(
                Objects.requireNonNull(key, what),
                (values == null) ? List.of() : values.stream().filter(Objects::nonNull).toList()));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Lowercase keys (the first of two names differing only in case wins), case-insensitive lookup, {@code null}
     * names and values dropped.
     */
    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, value) -> {
            if (name != null && value != null) {
                copy.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    /** A defensive copy of the raw request body (callers cannot mutate the stored bytes). */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
