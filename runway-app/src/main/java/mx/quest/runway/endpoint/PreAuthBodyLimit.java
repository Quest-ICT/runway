package mx.quest.runway.endpoint;

/**
 * The platform's pre-authentication body budget: the most request-body bytes a caller who is not signed in can make
 * the server read — {@code runway.endpoints.max-form-bytes} (default {@value RouteFormCaptureFilter#DEFAULT_MAX_FORM_BYTES}
 * bytes), or {@code runway.endpoints.max-body-bytes} if that is smaller. One number, applied by
 * {@link RouteFormCaptureFilter} (plugin-route forms), {@link EndpointDispatchFilter} (any plugin-route body of a
 * caller who is not signed in), {@link RouteAwareFormContentFilter} ({@code PUT}/{@code PATCH}/{@code DELETE} forms)
 * and {@link PreAuthBodyLimitFilter} (every other path, inside each realm's security chain).
 *
 * <p>A bean ({@link EndpointDispatchConfig}) so the security configurations take the configured value rather than
 * each reading the properties; {@link #filter()} makes one filter per chain.
 *
 * @param cap the budget in bytes ({@code >= 0})
 */
public record PreAuthBodyLimit(long cap) {

    public PreAuthBodyLimit {
        cap = Math.max(0, cap);
    }

    /** The budget for the given configuration: the smaller of the two keys, never negative. */
    public static PreAuthBodyLimit of(long maxBodyBytes, long maxFormBytes) {
        return new PreAuthBodyLimit(Math.min(maxBodyBytes, maxFormBytes));
    }

    /** A new {@link PreAuthBodyLimitFilter} at this budget, for {@code HttpSecurity.addFilterBefore(.., CsrfFilter.class)}. */
    public PreAuthBodyLimitFilter filter() {
        return new PreAuthBodyLimitFilter(cap);
    }
}
