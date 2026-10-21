package mx.quest.runway.spi.model;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The immutable result an {@link mx.quest.runway.spi.EndpointContributor} returns from
 * {@code handle} — a plain {@code (status, headers, body)} tuple the host sends back to the
 * client. Carrying no servlet types keeps handlers pure and testable.
 *
 * <p>The body is defensively copied; {@link #body()} returns a fresh copy on each call.
 * (As with {@link RouteRequest}, the record-generated {@code equals}/{@code hashCode} compare the
 * body array by <em>reference</em>, not content, so they are unreliable for an instance carrying a
 * body — do not rely on record equality for such instances.)
 *
 * @param status  the HTTP status code
 * @param headers the response headers (never {@code null}; defensively copied, unmodifiable;
 *                may be empty)
 * @param body    the response body (never {@code null}; defensively copied; empty when none)
 */
public record RouteResponse(int status, Map<String, String> headers, byte[] body) {

    public RouteResponse {
        headers = (headers == null) ? Map.of() : Map.copyOf(headers);
        body = (body == null) ? new byte[0] : body.clone();
    }

    /** A defensive copy of the response body. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** A {@code 200 OK} with the given body and no extra headers. */
    public static RouteResponse ok(byte[] body) {
        return new RouteResponse(200, Map.of(), body);
    }

    /** A {@code 404 Not Found} with an empty body. */
    public static RouteResponse notFound() {
        return new RouteResponse(404, Map.of(), new byte[0]);
    }

    /** A UTF-8 {@code text/plain} response with the given status and text. */
    public static RouteResponse text(int status, String body) {
        byte[] bytes = (body == null) ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return new RouteResponse(status, Map.of("Content-Type", "text/plain; charset=utf-8"), bytes);
    }
}
