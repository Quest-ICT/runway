package mx.quest.runway.protect;

/**
 * The minimal, immutable view of an incoming request handed to an {@link EdgeGuard} — just enough for an O(1)
 * abuse decision (the gated client IP, the method, and the path), with no servlet coupling, so a guard
 * implementation is a pure function and trivially testable.
 *
 * <p>{@code userAgent} is carried for two reasons: a guard may want to treat a declared crawler differently
 * from a browser, and — more importantly — it is the only field that lets a human reading a rejection tell
 * <i>what</i> was rejected. It is <b>untrusted client input</b>: freely spoofable and attacker-controlled, so
 * it may inform a log line or a heuristic but must never be the basis of a security decision on its own.
 *
 * @param clientIp  the gated client IP ({@code ClientIp.of(request)} — never a raw forwarded header)
 * @param method    the HTTP method
 * @param path      the request URI path
 * @param userAgent the declared {@code User-Agent}, or {@code null} when absent. Untrusted and spoofable.
 */
public record EdgeRequest(String clientIp, String method, String path, String userAgent) {

    /**
     * The pre-{@code userAgent} shape, kept so an {@link EdgeGuard} compiled against an earlier kernel keeps
     * loading — the protection plugin ships as an external artifact and is upgraded independently.
     */
    public EdgeRequest(String clientIp, String method, String path) {
        this(clientIp, method, path, null);
    }
}
