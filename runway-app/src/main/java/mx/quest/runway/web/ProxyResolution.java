package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * How {@link TrustedForwardedHeaderFilter} attributed one request: the connection it arrived on, whether that peer's
 * forwarded headers were honoured, the {@code X-Forwarded-For} chain it carried, and the client address the rest of
 * Runway sees ({@link ClientIp#of}). Kept on the request so an operator can check a proxy setup on <b>About Runway</b>
 * without signing in from a second address or reading logs. Read-only; holds nothing secret.
 *
 * @param peer         the TCP peer's address, before any forwarded header
 * @param peerTrusted  whether the peer is in {@code runway.proxy.trusted-cidrs}
 * @param honoured     whether the forwarded headers were used: the peer is trusted and
 *                     {@code runway.proxy.forwarded-headers} is on
 * @param forwardedFor the {@code X-Forwarded-For} chain as received (also when it was ignored)
 * @param clientIp     the client address Runway uses for this request
 */
public record ProxyResolution(String peer, boolean peerTrusted, boolean honoured, ForwardedFor.Chain forwardedFor,
                              String clientIp) {

    /** The request attribute that holds it. */
    public static final String ATTRIBUTE = ProxyResolution.class.getName();

    /** The resolution recorded on {@code request}, or {@code null} when the filter did not run for it. */
    public static ProxyResolution of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof ProxyResolution resolution ? resolution : null;
    }
}
