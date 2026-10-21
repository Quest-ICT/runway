package mx.quest.runway.protect;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mx.quest.runway.web.ClientIp;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The kernel reject seam at {@code FilterOrder.CORE_PROTECTION} — one slot inside the forwarded-header gate and
 * <b>outside</b> everything else, including the egress filters. It asks the injected {@link EdgeGuard} for an
 * O(1) verdict on each request; a reject is written as a tiny {@code Connection: close}, {@code no-store}
 * response and the chain is <b>not</b> called — so the request never reaches the Spring Security chain or a
 * controller (and, being outer of the egress filters, the reject is never buffered, rewritten or compressed).
 *
 * <p>With the kernel's permissive default guard this is a pass-through; the rich reject policy arrives with the
 * essential protection plugin.
 *
 * <p><b>Every rejection is handed to {@link EdgeRejectionRecorder}</b>, because this filter short-circuits
 * without calling the chain and therefore never reaches the access log at the other end of the ladder. Without
 * that call an edge {@code 403}/{@code 429} is invisible in every log the product writes. See the recorder for
 * why the logging is throttled and the metric is not.
 */
public class EdgeRejectFilter extends OncePerRequestFilter {

    private final EdgeGuard guard;
    private final EdgeRejectionRecorder recorder;

    public EdgeRejectFilter(EdgeGuard guard, EdgeRejectionRecorder recorder) {
        this.guard = guard;
        this.recorder = recorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        EdgeRequest edgeRequest = new EdgeRequest(ClientIp.of(request), request.getMethod(),
                request.getRequestURI(), request.getHeader("User-Agent"));
        EdgeVerdict verdict = guard.check(edgeRequest);
        if (verdict.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        // Record BEFORE writing: the response write can throw on a client that has already gone away (an
        // abusive one routinely has), and a rejection that is not counted because the abuser hung up is exactly
        // the rejection an operator most wants to see.
        recorder.record(edgeRequest, verdict);
        reject(response, verdict);
    }

    private static void reject(HttpServletResponse response, EdgeVerdict verdict) throws IOException {
        response.setStatus(verdict.status());
        response.setHeader("Connection", "close"); // don't keep-alive an abuser
        response.setHeader("Cache-Control", "no-store");
        if (verdict.retryAfterSeconds() > 0) {
            response.setHeader("Retry-After", Integer.toString(verdict.retryAfterSeconds()));
        }
        response.setContentType("text/plain;charset=UTF-8");
        byte[] body = message(verdict.status()).getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static String message(int status) {
        return switch (status) {
            case 403 -> "Forbidden.\n";
            case 429 -> "Too Many Requests.\n";
            default -> "Request rejected.\n";
        };
    }
}
