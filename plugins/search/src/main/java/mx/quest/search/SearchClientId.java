package mx.quest.search;

import mx.quest.runway.spi.model.RouteRequest;

/**
 * The per-client key for the search {@link SearchRateLimiter}: the request's client IP as the kernel resolved it
 * <b>after its trusted-proxy gate</b> ({@link RouteRequest#clientIp()}, SPI 0.5.0) — a forwarded header counts only
 * when the immediate peer is a configured trusted proxy, and never reaches the plugin otherwise. Search 1.x read the
 * first hop of {@code X-Forwarded-For} itself, which any visitor could set to a fresh value per request to evade the
 * limit.
 *
 * <p>When the kernel has no client IP for a request, every such request shares one {@value #SHARED} bucket, so it
 * still counts against <em>a</em> limit rather than escaping it.
 */
final class SearchClientId {

    static final String SHARED = "shared";

    private SearchClientId() {
    }

    /** The trusted client IP, else the shared fallback bucket. */
    static String of(RouteRequest request) {
        String ip = request.clientIp();
        return (ip == null || ip.isBlank()) ? SHARED : ip.strip();
    }
}
