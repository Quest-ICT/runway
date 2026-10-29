package mx.quest.runway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * The outermost filter ({@link FilterOrder#CORE_FORWARDED}): the one gate that decides whether to honor
 * {@code X-Forwarded-*} / {@code Forwarded} headers. It honors them — delegating to Spring's
 * {@link ForwardedHeaderFilter}, which rewrites scheme/host/port/remote-addr and strips the headers — <b>only</b>
 * when the immediate TCP peer ({@code request.getRemoteAddr()}, the raw connection address at this point) is in
 * the trusted-proxy {@link CidrSet}. From any other peer (or when forwarded handling is disabled, or the trust
 * set is empty) the headers are <b>hidden</b> from the entire downstream chain and the raw connection address
 * stands — so a directly-reachable instance cannot spoof its IP, host or scheme.
 *
 * <p>This replaces the container's ungated native handling (paired with {@code server.forward-headers-strategy:
 * none}), giving exactly one gated source that {@link ClientIp#of} reads. Honoring forwarded headers from any
 * peer is the classic spoofing foot-gun; the trust gate closes it.
 *
 * <p><b>The client address is Runway's own decision, not Spring's.</b> Spring takes the leftmost
 * {@code X-Forwarded-For} entry, which the client can write itself whenever the proxy appends to the header instead
 * of replacing it. This filter reads the chain from the right instead ({@link ForwardedFor#client}) and overrides the
 * remote address Spring computed. Spring still applies the forwarded scheme, host and port.
 *
 * <p><b>Only the {@code X-Forwarded-*} family is read.</b> The standard {@code Forwarded} header (RFC 7239) is hidden
 * even from a trusted peer: Spring prefers it over {@code X-Forwarded-*} whenever it is present, so a client could send
 * its own {@code Forwarded} through a proxy that only manages {@code X-Forwarded-*} and choose its address, host and
 * scheme. One family, managed by the proxy, leaves nothing for the client to add.
 *
 * <p>Every request records how it was attributed ({@link ProxyResolution}, for the About screen).
 */
public class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {

    /** The seven forwarded headers Spring recognizes — hidden wholesale from an untrusted peer. */
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Port",
            "X-Forwarded-Proto", "X-Forwarded-Prefix", "X-Forwarded-Ssl");

    /** The RFC 7239 header, hidden even from a trusted peer: Runway reads the {@code X-Forwarded-*} family only. */
    private static final Set<String> RFC_FORWARDED = Set.of("Forwarded");

    private final CidrSet trustedProxies;
    private final boolean enabled;

    public TrustedForwardedHeaderFilter(CidrSet trustedProxies, boolean enabled) {
        this.trustedProxies = trustedProxies;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String peer = request.getRemoteAddr();
        boolean peerTrusted = trustedProxies.contains(peer);
        ForwardedFor.Chain forwardedFor = ForwardedFor.chain(request.getHeaders(ForwardedFor.HEADER));
        if (enabled && peerTrusted) {
            String client = ForwardedFor.client(forwardedFor, trustedProxies, peer);
            request.setAttribute(ProxyResolution.ATTRIBUTE,
                    new ProxyResolution(peer, true, true, forwardedFor, client));
            // trusted: Spring applies scheme/host/port and strips the headers; the address is ours
            super.doFilterInternal(new ForwardedHeaderHidingRequest(request, RFC_FORWARDED), response,
                    (req, res) -> chain.doFilter(new ClientAddressRequest((HttpServletRequest) req, client), res));
        } else {
            request.setAttribute(ProxyResolution.ATTRIBUTE,
                    new ProxyResolution(peer, peerTrusted, false, forwardedFor, peer));
            // untrusted: hide them, raw peer stands
            chain.doFilter(new ForwardedHeaderHidingRequest(request, FORWARDED_HEADERS), response);
        }
    }

    /** Presents the client address this filter resolved, over whatever Spring derived from the leftmost entry. */
    private static final class ClientAddressRequest extends HttpServletRequestWrapper {

        private final String client;

        ClientAddressRequest(HttpServletRequest request, String client) {
            super(request);
            this.client = client;
        }

        @Override
        public String getRemoteAddr() {
            return client;
        }

        @Override
        public String getRemoteHost() {
            return client; // never a reverse lookup
        }
    }

    /** Hides the given forwarded headers so no downstream code (or container default) can read a spoofed value. */
    private static final class ForwardedHeaderHidingRequest extends HttpServletRequestWrapper {

        private final Set<String> hidden;

        ForwardedHeaderHidingRequest(HttpServletRequest request, Set<String> hidden) {
            super(request);
            this.hidden = hidden;
        }

        private boolean isForwarded(String name) {
            return name != null && hidden.stream().anyMatch(header -> header.equalsIgnoreCase(name));
        }

        @Override
        public String getHeader(String name) {
            return isForwarded(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isForwarded(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public int getIntHeader(String name) {
            // HttpServletRequestWrapper delegates getIntHeader/getDateHeader straight to the wrapped request,
            // NOT through getHeader — so they must be hidden explicitly or a spoofed X-Forwarded-Port leaks.
            return isForwarded(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public long getDateHeader(String name) {
            return isForwarded(name) ? -1L : super.getDateHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            Enumeration<String> all = super.getHeaderNames();
            while (all.hasMoreElements()) {
                String name = all.nextElement();
                if (!isForwarded(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }
}
