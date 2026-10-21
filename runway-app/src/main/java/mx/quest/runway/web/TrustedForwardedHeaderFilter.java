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
 */
public class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {

    /** The seven forwarded headers Spring recognizes — hidden wholesale from an untrusted peer. */
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Port",
            "X-Forwarded-Proto", "X-Forwarded-Prefix", "X-Forwarded-Ssl");

    private final CidrSet trustedProxies;
    private final boolean enabled;

    public TrustedForwardedHeaderFilter(CidrSet trustedProxies, boolean enabled) {
        this.trustedProxies = trustedProxies;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled && trustedProxies.contains(request.getRemoteAddr())) {
            super.doFilterInternal(request, response, chain); // trusted: honor + strip forwarded headers
        } else {
            chain.doFilter(new ForwardedHeaderHidingRequest(request), response); // untrusted: hide them, raw peer stands
        }
    }

    private static boolean isForwarded(String name) {
        return name != null && FORWARDED_HEADERS.stream().anyMatch(header -> header.equalsIgnoreCase(name));
    }

    /** Hides the forwarded headers so no downstream code (or container default) can read a spoofed value. */
    private static final class ForwardedHeaderHidingRequest extends HttpServletRequestWrapper {

        ForwardedHeaderHidingRequest(HttpServletRequest request) {
            super(request);
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
