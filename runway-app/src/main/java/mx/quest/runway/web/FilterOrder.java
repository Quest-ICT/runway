package mx.quest.runway.web;

import org.springframework.core.Ordered;

/**
 * The single authority for servlet-filter placement across the kernel — the realized integer ladder behind
 * the five conceptual request-pipeline bands (EDGE_INGRESS → SECURITY → TENANT → APPLICATION → EDGE_EGRESS). Every
 * kernel filter pins its order to a constant here, and a plugin's {@link mx.quest.runway.spi.FilterBand}+offset is mapped onto
 * this same scale by {@link PluginFilterPlacement}, so there is exactly one place that decides who wraps whom.
 *
 * <h2>The scale</h2>
 * Orders are on Spring's {@link Ordered} scale: <b>lower = outer</b>. A filter <em>wraps</em> — its
 * {@code pre} logic runs on the way in and its {@code post} logic on the way out — so the <b>outermost</b>
 * filter (lowest order) runs its {@code pre} <b>first</b> and its {@code post} <b>last</b>. The bands are
 * phases on the request→response timeline, not literal positions: an EDGE_EGRESS filter does its work in its
 * {@code post}, so it is registered <b>outermost</b> (low order) precisely so its {@code post} runs near the
 * wire — "egress = last on the response" while the filter sits outermost in the nesting.
 *
 * <h2>The realized ladder (outermost → innermost)</h2>
 * <pre>
 *   CORE_FORWARDED        Integer.MIN_VALUE   EDGE_INGRESS  TrustedForwardedHeaderFilter (gates X-Forwarded-*)
 *   CORE_PROTECTION       MIN_VALUE + 1       EDGE_INGRESS  EdgeRejectFilter (the O(1) reject seam)
 *   CORE_ACCESS_LOG       -500                (own slot)    AccessLogFilter (one line per request past the floor)
 *   CORE_BANDWIDTH        -300                EDGE_EGRESS   BandwidthFilter (meters the on-the-wire bytes)
 *   CORE_SITE_METRICS     -290                EDGE_EGRESS   SiteRequestMetricsFilter (per-site request timing)
 *   CORE_COMPRESSION      -200                EDGE_EGRESS   CompressionFilter (brotli / gzip, one decision)
 *   CORE_CSP_FINALIZE     -160                EDGE_EGRESS   CspFilter (sets the CSP header on text/html)
 *   CORE_HEAD_INJECTION   -150                EDGE_EGRESS   HeadInjectionFilter (splices &lt;head&gt; markup)
 *   CORE_SITE_CONTEXT      50                 TENANT        SiteContextFilter (resolves + binds the site)
 *   CORE_SITE_HEALTH       55                 TENANT        SiteHealthFilter (the per-site /__health probe)
 *   CORE_SITE_RELOAD       56                 TENANT        SiteReloadFilter (loopback /__reload/**)
 *   CORE_CONSOLE_HOST      57                 TENANT        ConsoleHostFilter (operator console off site hosts)
 *   CORE_ROUTE_FORM_CAPTURE 58                TENANT        RouteFormCaptureFilter (reads a plugin route's form body)
 *   CORE_FORM_CONTENT      59                 TENANT        RouteAwareFormContentFilter (PUT/PATCH/DELETE forms)
 *   CORE_SUSPENDED_SITE    60                 TENANT        SuspendedSiteFilter (503s a suspended tenant)
 *   CORE_QUARANTINED_SITE  65                 TENANT        QuarantinedSiteFilter (503s a quarantined admin)
 *   CORE_MAINTENANCE       68                 TENANT        MaintenanceSiteFilter (503s a self-maintenance tenant)
 *   CORE_BANDWIDTH_LIMIT   69                 TENANT        BandwidthLimitFilter (503s a tenant over its quota)
 *   CORE_EDGE_MODE         70                 TENANT        (reserved — read-only edge/replica topology, later)
 *   CORE_SECURITY         100                 SECURITY      the Spring Security FilterChainProxy
 * </pre>
 *
 * <h2>Why these values</h2>
 * <ul>
 *   <li><b>The floor is absolute.</b> {@link #CORE_FORWARDED} and {@link #CORE_PROTECTION} take the two most
 *       outer integers so the trusted-proxy gate and the reject seam run before anything else. A reject from
 *       {@link #CORE_PROTECTION} short-circuits without calling the chain, so it <b>never reaches the Spring
 *       Security chain or a controller</b> (it is still a servlet filter — it does sit inside the container's
 *       filter chain, just outermost).</li>
 *   <li><b>Egress filters are strongly negative</b> ({@code -200..-150}) so they wrap the whole render and
 *       their {@code post} runs close to the wire, yet they stay <b>inner</b> of the protection floor — so a
 *       reject is emitted before they ever run. Among themselves, lower = outer means compression
 *       ({@code -200}) wraps head-injection ({@code -150}): on the way out head-injection's {@code post} runs
 *       first (rewrites the HTML) and compression's runs last (encodes it) — inject-then-compress.</li>
 *   <li><b>The TENANT and SECURITY filters use small positive integers</b> consistent with
 *       {@code spring.security.filter.order} (which is set to {@link #CORE_SECURITY} = {@code 100}). This is
 *       the linchpin of tenant isolation: the security proxy ({@code 100}) is kept <b>inner</b> of
 *       {@link #CORE_SITE_CONTEXT} ({@code 50}) so the realm chains and every tenant-scoped decision read a
 *       <b>bound</b> {@code SiteResolver.current()}. Reverting to Spring Boot's default ({@code -100}) would
 *       run security before the site is resolved and silently break isolation — guarded by a chain-order
 *       test.</li>
 * </ul>
 *
 * <h2>The hard floor rule</h2>
 * A plugin filter may <b>never</b> sit before {@link #CORE_PROTECTION}: the earliest a plugin can reach
 * (EDGE_INGRESS with the minimum offset) is {@code CORE_PROTECTION + 1}. {@link #CORE_FORWARDED} and
 * {@link #CORE_PROTECTION} are kernel-only. Plugins declare a {@link mx.quest.runway.spi.FilterBand} + small offset, never a raw
 * order, so a plugin can never preempt core protection by choosing a low number — bands, not a single global
 * order space, are what make that structurally impossible.
 *
 * <h2>Caveat for egress filters</h2>
 * The egress filters ({@code -200..-150}) are <b>outer</b> of {@link #CORE_SITE_CONTEXT}, so their {@code pre}
 * runs <b>before</b> the site is bound. This is only safe because egress filters act in {@code post} and must
 * <b>never</b> read {@code SiteResolver.current()} (e.g. {@code HeadInjectionFilter} consumes a stashed
 * request attribute instead). Any future egress filter must obey the same contract.
 */
public final class FilterOrder {

    /** Trusted-proxy header gate — strictly outermost, so the client IP/host/scheme are settled first. */
    public static final int CORE_FORWARDED = Ordered.HIGHEST_PRECEDENCE;

    /** The EDGE_INGRESS reject seam (DoS/abuse floor) — one slot inside the forwarded gate. Kernel-only. */
    public static final int CORE_PROTECTION = Ordered.HIGHEST_PRECEDENCE + 1;

    /**
     * Bandwidth metering — outer than {@link #CORE_COMPRESSION} so it counts the <b>real on-the-wire bytes</b>
     * (after any compression), yet inner than {@link #CORE_FORWARDED} so the host it attributes to is settled.
     */
    public static final int CORE_BANDWIDTH = -300;

    /**
     * Per-site request metrics ({@code SiteRequestMetricsFilter}) — just inside bandwidth, so it times almost
     * the whole inner chain while the host is still settled. Timing only; it never wraps or mutates the response.
     */
    public static final int CORE_SITE_METRICS = -290;

    /**
     * {@code CompressionFilter} — the kernel's single response-compression filter (brotli preferred, gzip
     * fallback; Jetty's own gzip stays off). Outer of {@link #CORE_CSP_FINALIZE} and {@link #CORE_HEAD_INJECTION},
     * so it encodes the already-spliced, already-headed body and recomputes {@code Content-Length} for what it
     * sends; inner of {@link #CORE_BANDWIDTH}, so the meter counts the compressed bytes.
     */
    public static final int CORE_COMPRESSION = -200;

    /**
     * {@code CspFilter} — sets the Content-Security-Policy header on every {@code text/html} response, between
     * compression and head-injection: outer of head-injection (so the head markup is already spliced when the
     * header is stamped) and inner of compression (so the finished bytes are encoded afterwards). Report-only by
     * default. It does finalize the nonce here: a per-request nonce + {@code 'strict-dynamic'} on the kernel
     * surfaces, and on public pages the ETag-derived nonce the page controller publishes (the body half of which
     * head-injection stamps). {@code script-src} carries no {@code 'unsafe-inline'}.
     */
    public static final int CORE_CSP_FINALIZE = -160;

    /** {@code HeadInjectionFilter} — innermost of the egress trio, so its post runs before compression's. */
    public static final int CORE_HEAD_INJECTION = -150;

    /** {@code SiteContextFilter} — outermost of the TENANT band; everything tenant-scoped depends on it. */
    public static final int CORE_SITE_CONTEXT = 50;

    /** {@code SuspendedSiteFilter} — after site binding (resolve-then-check). */
    /**
     * {@code SiteHealthFilter} — the per-site health probe ({@code /__health}) on a tenant host.
     *
     * <p><b>This slot is the whole design, and it is load-bearing.</b> It sits INNER of
     * {@link #CORE_SITE_CONTEXT} (50), the only place the site is bound from the Host, so the probe knows which
     * tenant it is answering for; and OUTER of every tenant-band gate — {@link #CORE_SUSPENDED_SITE} (60),
     * {@link #CORE_QUARANTINED_SITE} (65), {@link #CORE_ESSENTIAL_FLOOR} (67), {@link #CORE_MAINTENANCE} (68),
     * {@link #CORE_BANDWIDTH_LIMIT} (69) — so it can REPORT those states instead of being 503'd by them. A health
     * check that goes down together with the thing it monitors is worse than no health check, which is why an
     * {@code EndpointContributor} or an {@code @Controller} cannot serve this: both dispatch at or inside the
     * security chain (100), i.e. inner of all five gates.
     *
     * <p><b>Nothing may be registered in 51..54</b>, and this filter must never move inward. Inward drift fails
     * silently and only in the states nobody checks by hand, so it is pinned by both {@code FilterOrderTest} (the
     * constants) and {@code FilterChainOrderTest} (the real resolved registrations).
     */
    public static final int CORE_SITE_HEALTH = 55;

    /**
     * {@code SiteReloadFilter} — the loopback-only, token-gated site-reload endpoint ({@code /__reload/**}).
     *
     * <p>Sits beside {@link #CORE_SITE_HEALTH} and for the same reason: outer of every tenant gate (60..69), so a
     * suspended or maintenance-mode catch-all site cannot 503 the very request that would re-read the config
     * fixing it. It needs no bound site (it names the site by id), but sitting here keeps it immune to the gates.
     */
    public static final int CORE_SITE_RELOAD = 56;

    /**
     * {@code ConsoleHostFilter} — refuses the operator console ({@code /__app/**}, {@code /app/login|logout},
     * {@code /app/forgot-password|reset}) with a 404 on host names it must not answer on (a host that serves a site
     * other than the default one, or one not in {@code runway.console.hosts}). Outer of {@link #CORE_SECURITY} so
     * the sign-in page and recovery are refused before any session, CSRF token or login attempt is touched; outer
     * of the multipart filter (90) so an upload to a refused host is not parsed. It looks the host up itself, so
     * it needs no bound site.
     */
    public static final int CORE_CONSOLE_HOST = 57;

    /**
     * {@code RouteFormCaptureFilter} — reads the form body of a request bound for a plugin route once, under the
     * endpoint body cap, so the CSRF check's {@code getParameter()} cannot consume it before the route dispatcher
     * builds {@code RouteRequest.form()}. Inner of {@link #CORE_SITE_CONTEXT} (it serves only requests with a bound
     * site, like the dispatcher); outer of the tenant gates and of the plugin TENANT window ({@code 71..99}), so no
     * plugin filter between site binding and {@link #CORE_SECURITY} can read the body first.
     */
    public static final int CORE_ROUTE_FORM_CAPTURE = 58;

    public static final int CORE_SUSPENDED_SITE = 60;

    /** {@code QuarantinedSiteFilter} — inner of suspended, outer of security; 503s a quarantined admin surface. */
    public static final int CORE_QUARANTINED_SITE = 65;

    /** {@code EssentialFloorFilter} — fail-closed 503 for tenant hosts when an essential plugin is missing/broken. */
    public static final int CORE_ESSENTIAL_FLOOR = 67;

    /**
     * {@code MaintenanceSiteFilter} — inner of the operator/integrity blocks above, outer of security; 503s a
     * tenant that has put its <em>own</em> public site into maintenance, while exempting the tenant admin realm
     * ({@code /__admin}, {@code /login}) so the site manager can still toggle it back off.
     */
    public static final int CORE_MAINTENANCE = 68;

    /**
     * {@code BandwidthLimitFilter} — inner of maintenance, outer of security; 503s a site's public traffic
     * once it exceeds its configured rolling-30-day outbound quota (tenant console realm exempt).
     */
    public static final int CORE_BANDWIDTH_LIMIT = 69;

    /** Reserved: read-only edge/replica blocking (later). */
    public static final int CORE_EDGE_MODE = 70;

    /**
     * The Spring Security {@code FilterChainProxy}. Mirrors {@code spring.security.filter.order} in
     * {@code application.yml}; kept inner of the TENANT filters so the realm chains see a bound site.
     */
    public static final int CORE_SECURITY = 100;

    /**
     * The kernel route dispatcher for {@code EndpointContributor} routes (robots.txt, sitemap, …) — a terminal
     * application handler, inner of the plugin APPLICATION window so a plugin filter can still wrap it. It
     * short-circuits a matched route; the access log, far outside it, still records the answer.
     */
    public static final int CORE_ENDPOINT_DISPATCH = Integer.MAX_VALUE - 100;

    /**
     * {@code AccessLogFilter} — inner of the whole EDGE_INGRESS band (the kernel's reject floor and any plugin
     * ingress filter) and outer of everything else, so every request that gets past the abuse floor is logged,
     * whoever answers it: a tenant gate's {@code 503}, Spring Security (a login attempt, a CSRF {@code 403}), a
     * slow body's {@code 408}, an endpoint route, a page. Its {@code post} runs after every inner filter has
     * returned, so the status it reads is final.
     *
     * <p>Until 1.1.2 it sat innermost ({@code Integer.MAX_VALUE}), and every filter that answered without calling
     * the chain answered unseen: a password-guessing run against a site's {@code /login} left no line at all.
     * Floor rejections stay out on purpose: a flood the floor turns away in O(1) must not become a flood of log
     * writes; {@code EdgeRejectionRecorder} reports them, throttled, under {@code runway.protect}.
     */
    public static final int CORE_ACCESS_LOG = -500;

    /**
     * {@code RouteAwareFormContentFilter} (Boot's {@code FormContentFilter}, which parses a {@code PUT}/{@code PATCH}/
     * {@code DELETE} form body into parameters, bounded). Boot's own order is {@code -9900}, outer of the access log,
     * where its {@code 413} for an oversized body went unlogged. Here it is inner of the site binding, so the refusal
     * is in that site's log too, and inner of {@link #CORE_ROUTE_FORM_CAPTURE}, which must read a route's body
     * first; and outer of the tenant gates, every plugin TENANT filter and the security chain, which read
     * parameters (the CSRF check above all).
     */
    public static final int CORE_FORM_CONTENT = 59;

    private FilterOrder() {
    }
}
