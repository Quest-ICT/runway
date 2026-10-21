package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The single sanctioned way to read the client IP. It returns {@link HttpServletRequest#getRemoteAddr()} —
 * which is the <b>gated</b> source: {@link TrustedForwardedHeaderFilter} (the outermost filter, at
 * {@link FilterOrder#CORE_FORWARDED}) has already rewritten the remote address from {@code X-Forwarded-For}
 * <em>only</em> when the immediate peer is a trusted proxy, and otherwise left the raw connection address.
 *
 * <p><b>Never read {@code X-Forwarded-For} (or any {@code Forwarded}/{@code X-Forwarded-*} header) directly
 * downstream</b> — doing so bypasses the trust gate and lets a directly-reachable instance spoof its IP. The
 * DoS floor, the login-attempt hook, the access log, and every rate/abuse decision read here and only here.
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** The gated client IP for this request (equal to {@code request.getRemoteAddr()}). */
    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
