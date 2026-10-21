package mx.quest.runway.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound {@code runway.csp.*} configuration for the kernel's Content-Security-Policy edge filter
 * ({@link CspFilter}, wired at {@link FilterOrder#CORE_CSP_FINALIZE}). One policy string is built once from
 * these values and set on every {@code text/html} response.
 *
 * <h2>Report-only by default — the deliberate choice</h2>
 * {@link #mode} defaults to {@link Mode#REPORT}, so out of the box the browser sends the
 * {@code Content-Security-Policy-Report-Only} header: violations flow to {@code /__csp-report}
 * (logged by {@link CspReportController}) but <b>nothing is ever blocked</b>. An operator reviews the
 * reports, widens {@link #extraHosts} if a legitimate resource was flagged, then switches to
 * {@link Mode#ENFORCE}. This means CSP can never break a page the moment it ships.
 *
 * <h2>Self-only policy</h2>
 * Runway <b>self-hosts</b> every vendor asset (Tabler / FontAwesome / HTMX / Alpine / Chart.js / CodeMirror)
 * under {@code /__assets/**}, so the default policy needs <b>no third-party CDN host</b>. The directives are
 * therefore {@code 'self'}-only (plus {@code data:} for images
 * and the {@code 'unsafe-inline'} the two script/style directives need — see below). An operator who adds a
 * captcha provider or a CDN widens the policy through {@link #extraHosts}, never by editing code.
 *
 * <h2>{@code script-src} is {@code 'self'} (no {@code 'unsafe-inline'}); {@code style-src} still allows it</h2>
 * Runway ships <b>no executable inline {@code <script>}</b> — the handful of first-party ones (the console
 * page-editor/messages/restart clients and the reCAPTCHA-v3 client) are served from {@code /__assets/**}, and
 * JSON-LD is {@code type="application/ld+json"} data (not governed by {@code script-src}) — so {@code script-src}
 * drops {@code 'unsafe-inline'} and actually defends against injected inline script. {@code style-src} keeps
 * {@code 'unsafe-inline'} because pages still carry inline {@code style=} attributes; tightening it is a separate,
 * larger refactor. {@code script-src} additionally <b>leads with a per-request {@code 'nonce-<x>'}</b>
 * ({@link #NONCE_PLACEHOLDER}, substituted per response by {@link CspFilter}), a step toward a nonce +
 * {@code 'strict-dynamic'} model that will retire the host allowlist. Without {@code 'strict-dynamic'} the nonce is
 * <b>additive</b>, so {@code 'self'} still authorizes every externalized first-party script and no load decision changes.
 *
 * <h2>Plugin (CAPTCHA) hosts are directive-scoped</h2>
 * A {@link CspHostContributor} (the protection plugin's CAPTCHA domains) widens ONLY {@code script-src},
 * {@code connect-src} and {@code frame-src} — what a third-party widget actually needs — never
 * {@code style/img/font/default-src}. Operator {@link #extraHosts} still widen the full fetch surface.
 *
 * <h2>Reporting + extra hardening</h2>
 * The policy emits {@code object-src 'none'} always, {@code upgrade-insecure-requests} when
 * {@link #upgradeInsecureRequests} is on, and both {@code report-uri} (legacy) and {@code report-to} (the modern
 * Reporting-API group, paired with a {@code Reporting-Endpoints} response header set by {@link CspFilter}).
 *
 * <p>Each {@code *Src} property is the value of one directive (the tokens after the directive name). They carry
 * sensible {@code 'self'}-only defaults; an operator may override any of them wholesale in {@code application.yml},
 * or — the common case — leave them and just append hosts through {@link #extraHosts}, which are added to the
 * fetch directives ({@code default/script/style/img/font/connect}) and to {@code frame-src} (so an embedded
 * captcha/widget iframe is allowed). {@link #reportUri} is where the browser POSTs violation reports; it points
 * at the in-kernel collector and should not normally be changed.
 */
@ConfigurationProperties("runway.csp")
public class RunwayCspProperties {

    /** How the policy is delivered: not at all, report-only (the default), or enforced. */
    public enum Mode {
        /** No CSP header is set at all. */
        OFF,
        /** {@code Content-Security-Policy-Report-Only} — violations are reported, never blocked. The default. */
        REPORT,
        /** {@code Content-Security-Policy} — violations are blocked. Opt-in, after reviewing reports. */
        ENFORCE
    }

    /** The delivery mode. Default {@link Mode#REPORT} so a fresh install reports without ever breaking a page. */
    private Mode mode = Mode.REPORT;

    /** {@code default-src} — the fallback for any fetch directive not set explicitly. Default {@code 'self'}. */
    private String defaultSrc = "'self'";

    /**
     * {@code script-src}. {@code 'self'} only — Runway ships <b>no</b> executable inline {@code <script>}: the
     * few first-party ones (the console editor/messages/restart clients and the reCAPTCHA-v3 client) are served
     * from {@code /__assets/**}, and JSON-LD ({@code type="application/ld+json"}) is data, not script. So
     * {@code 'unsafe-inline'} is deliberately absent — the policy actually defends against injected inline script.
     * (Alpine.js is loaded but not used with expressions, so no {@code 'unsafe-eval'} is needed either.)
     */
    private String scriptSrc = "'self'";

    /**
     * {@code style-src}. {@code 'unsafe-inline'} is <b>kept</b> on purpose: pages still carry many inline
     * {@code style=} attributes (the console chrome and tenant templates), and removing it needs a template
     * refactor or {@code 'unsafe-hashes'} — a separate, larger project than the script-src tightening.
     */
    private String styleSrc = "'self' 'unsafe-inline'";

    /** {@code img-src}. {@code data:} allows inlined data-URI images (favicons, small SVGs). */
    private String imgSrc = "'self' data:";

    /** {@code font-src}. Self-hosted fonts only by default. */
    private String fontSrc = "'self'";

    /** {@code connect-src} — XHR/fetch/WebSocket targets. Same-origin only by default (HTMX posts back home). */
    private String connectSrc = "'self'";

    /** {@code base-uri} — restricts {@code <base href>} so an injected tag cannot re-root relative URLs. */
    private String baseUri = "'self'";

    /** {@code form-action} — where forms may submit. Same-origin only. */
    private String formAction = "'self'";

    /** {@code frame-ancestors} — who may frame our pages. {@code 'self'} mirrors the {@code SAMEORIGIN} frame-options. */
    private String frameAncestors = "'self'";

    /** Where the browser POSTs violation reports. The in-kernel collector; changing it is almost never right. */
    private String reportUri = "/__csp-report";

    /**
     * Extra hosts an operator allowlists <b>without editing code</b> — appended to the fetch directives
     * ({@code default/script/style/img/font/connect}) and to {@code frame-src}. The place to add a captcha or
     * CDN host: e.g. {@code https://challenges.cloudflare.com} for Cloudflare Turnstile. Empty by default
     * (Runway self-hosts everything, so nothing external is needed out of the box).
     */
    private List<String> extraHosts = new ArrayList<>();

    /**
     * {@code upgrade-insecure-requests} — when true, the browser rewrites any {@code http://} subresource to
     * {@code https://} before fetching. Off by default: it is only meaningful under {@code https} + enforce, and
     * a plain-HTTP-behind-proxy development machine should not be surprised. Runway self-hosts its assets (same scheme) and
     * the only cross-origin hosts are the {@code https} CAPTCHA providers, so there is essentially no mixed
     * content to upgrade — enable it as belt-and-braces once the estate is fully {@code https}.
     */
    private boolean upgradeInsecureRequests = false;

    /**
     * {@code 'strict-dynamic'} on {@code script-src} — the migration's end state. When on (the default), the nonced
     * <b>kernel surfaces</b> (console / login / recovery / wizard) emit {@code script-src 'nonce-<x>' 'strict-dynamic'
     * 'self' <hosts>}: a modern browser trusts the nonced scripts (and scripts they load, e.g. reCAPTCHA's) and
     * <b>ignores</b> the {@code 'self'} + host allowlist — closing the "host allowlist can be bypassed" gap — while
     * the allowlist stays as a pre-strict-dynamic fallback for old browsers. Public tenant pages get it only through
     * {@link #publicStrictDynamic} (an ETag-derived nonce stamped at the edge). Turn this <b>off</b> to
     * roll the whole estate — public pages included — back to the host-allowlist behaviour without a redeploy.
     */
    private boolean strictDynamic = true;

    /**
     * {@code 'strict-dynamic'} on <b>public tenant pages</b>. When on — and {@link #strictDynamic} is on
     * and the mode is not {@link Mode#OFF} — every page response that carries an ETag gets a nonce
     * <b>derived from that ETag</b> ({@link PublicPageNonce}: stable per content version, so shared caching and
     * {@code 304} revalidation keep working), {@code 'strict-dynamic'} on {@code script-src}, and that nonce
     * stamped at the response edge on every executable {@code <script>} the host-allowlist policy would have allowed
     * ({@link #scriptAllowlist}: same-origin or an allowlisted host; never an inline script unless
     * {@code 'unsafe-inline'} is configured) — so nothing the allowlist blocked starts running. Responses without a page ETag
     * (error pages, plugin endpoints, the maintenance page) keep the host-allowlist policy. Default {@code true};
     * set {@code false} to put public pages back on {@code 'self'} + host allowlist (moves every page ETag once).
     */
    private boolean publicStrictDynamic = true;

    /** The Reporting-API group name shared by the CSP {@code report-to} directive and the {@code Reporting-Endpoints} header. */
    private static final String REPORT_GROUP = "csp-endpoint";

    /**
     * The literal token standing in for the per-request nonce <b>source-expression</b> inside the built
     * {@code script-src} value. The policy is still built <b>once</b> ({@link #buildPolicy}); {@link CspFilter}
     * substitutes it at header-stamp time with {@code "'nonce-<base64url>' "} — a fresh per-request value on a nonced
     * kernel surface, the ETag-derived {@link PublicPageNonce} on a public page response that carries one — or
     * {@code ""} on any other response (a per-request nonce there would be inert yet make an otherwise-identical
     * <em>shared-cacheable</em> response vary per request).
     * The token therefore spans the whole expression <b>and its trailing space</b>, so it can vanish cleanly.
     *
     * <p>The braces make it collision-proof — a base64url nonce (alphabet {@code A-Za-z0-9-_}) can never contain
     * {@code '{'} or {@code '}'} — and it is printable (never a control-char delimiter). It is
     * always replaced before the header reaches the wire, so the literal token never ships.
     */
    public static final String NONCE_PLACEHOLDER = "{RW_CSP_NONCE}";

    /**
     * The literal token standing in for the per-request {@code 'strict-dynamic'} decision inside {@code script-src}
     * (present only when {@link #strictDynamic} is on). {@link CspFilter} resolves it to {@code "'strict-dynamic' "}
     * on the <b>nonced kernel surfaces</b> (console / login / recovery / wizard — the templates that carry the
     * nonce) and on a
     * public page response carrying a {@link PublicPageNonce} (whose body scripts the edge stamped), and to
     * {@code ""} everywhere else, so any other response keeps {@code 'self' <hosts>} and its un-nonced scripts still
     * load. Brace-delimited ⇒ collision-proof vs a base64url nonce; always substituted before the header ships.
     */
    public static final String STRICT_DYNAMIC_PLACEHOLDER = "{RW_CSP_SD}";

    /**
     * The HTTP header name for the current mode, or {@code null} when {@link Mode#OFF}. {@link Mode#REPORT} ⇒
     * {@code Content-Security-Policy-Report-Only} (report, never block); {@link Mode#ENFORCE} ⇒
     * {@code Content-Security-Policy}.
     */
    public String headerName() {
        return switch (mode) {
            case OFF -> null;
            case REPORT -> "Content-Security-Policy-Report-Only";
            case ENFORCE -> "Content-Security-Policy";
        };
    }

    /** Builds the policy from the configured directives + the operator's {@link #extraHosts} only. */
    public String buildPolicy() {
        return buildPolicy(List.of());
    }

    /**
     * Builds the immutable policy string from the configured directives, appending the operator's
     * {@link #extraHosts} <b>and</b> {@code pluginHosts} (from {@link CspHostContributor}s — e.g. the CAPTCHA
     * providers' domains) to the fetch directives and emitting an explicit {@code frame-src} for them (so an
     * embedded captcha iframe is allowed). {@link #reportUri} is emitted last as {@code report-uri} when set.
     * Called once by {@link CspFilter} at construction; the result is a cached policy <b>template</b> — it carries
     * {@link #NONCE_PLACEHOLDER} inside {@code script-src} — so per-request cost is one {@code String.replace}
     * (the fresh nonce) + one {@code setHeader}.
     *
     * @param pluginHosts extra source hosts contributed by plugins (may be empty; blanks/dupes are dropped)
     */
    public String buildPolicy(List<String> pluginHosts) {
        return buildPolicy(pluginHosts, java.util.Map.of());
    }

    /**
     * As {@link #buildPolicy(List)}, plus one site's own additions (directive → sources) from the operator's approved
     * embed hosts. Each source joins only its directive: {@code frame-src} and {@code media-src}, absent from the
     * platform policy, are emitted with what they would otherwise fall back to ({@code 'self'} plus the platform's
     * hosts for frames; {@link #defaultSrc} plus the operator's hosts for media) followed by the site's sources.
     * {@code script-src} is never extended per site (see {@code EmbedHostService.DIRECTIVES}).
     *
     * @param siteAdditions directive → sources; empty for the platform policy
     */
    public String buildPolicy(List<String> pluginHosts, java.util.Map<String, List<String>> siteAdditions) {
        java.util.Map<String, List<String>> site = siteAdditions == null ? java.util.Map.of() : siteAdditions;
        // Operator extraHosts widen the full fetch surface (an operator adds a CDN/host and expects it everywhere).
        // Plugin (e.g. CAPTCHA) hosts are scoped to ONLY the directives a third-party widget actually needs — its
        // script, its verification/telemetry connections, and its challenge iframe — never style/img/font/default
        // Both sets are deduped; the combined set is used for the script/connect/frame directives.
        String operatorHosts = normalizedHosts(extraHosts);
        String scriptFrameHosts = scriptFrameHosts(pluginHosts); // operator + plugin, deduped
        String opSuffix = suffix(operatorHosts);
        String scfSuffix = suffix(scriptFrameHosts);

        List<String> d = new ArrayList<>();
        directive(d, "default-src", defaultSrc, opSuffix);
        // A per-request nonce leads script-src (CspFilter substitutes {@link #NONCE_PLACEHOLDER} per response),
        // then — when {@link #strictDynamic} is on — a {@link #STRICT_DYNAMIC_PLACEHOLDER} the filter resolves to
        // " 'strict-dynamic'" on nonced kernel surfaces and "" elsewhere; the configured value ('self' by default)
        // + host allowlist stay AFTER it as the pre-strict-dynamic fallback.
        directive(d, "script-src",
                NONCE_PLACEHOLDER + (strictDynamic ? STRICT_DYNAMIC_PLACEHOLDER : "") + scriptSrc, scfSuffix);
        directive(d, "style-src", styleSrc, plus(opSuffix, site.get("style-src")));
        directive(d, "img-src", imgSrc, plus(opSuffix, site.get("img-src")));
        directive(d, "font-src", fontSrc, plus(opSuffix, site.get("font-src")));
        directive(d, "connect-src", connectSrc, plus(scfSuffix, site.get("connect-src")));
        if (site.containsKey("media-src")) {
            directive(d, "media-src", defaultSrc, plus(opSuffix, site.get("media-src")));
        }
        directive(d, "object-src", "'none'", ""); // CSP-D: nothing legitimate needs <object>/<embed>/<applet>
        directive(d, "base-uri", baseUri, "");
        directive(d, "form-action", formAction, "");
        directive(d, "frame-ancestors", frameAncestors, "");
        // Only emit frame-src when there ARE hosts to frame (a captcha challenge or an operator widget); with none,
        // an absent frame-src falls back to default-src ('self'), which is exactly what we want out of the box.
        String frameSuffix = plus(scfSuffix, site.get("frame-src"));
        if (!frameSuffix.isBlank()) {
            directive(d, "frame-src", "'self'", frameSuffix);
        }
        if (upgradeInsecureRequests) {
            d.add("upgrade-insecure-requests"); // CSP-D: a valueless directive
        }
        if (reportUri != null && !reportUri.isBlank()) {
            d.add("report-uri " + reportUri.trim());       // legacy — still honored by most browsers
            d.add("report-to " + REPORT_GROUP);            // CSP-C: the Reporting-API group (header set by CspFilter)
        }
        return String.join("; ", d);
    }

    /**
     * The {@code script-src} source list of the <b>host-allowlist policy</b> — the configured {@link #scriptSrc} plus
     * {@link #extraHosts} plus {@code pluginHosts}, the same tokens {@link #buildPolicy(List)} writes after the nonce —
     * as a {@link CspScriptAllowlist}. The public-page edge stamps the nonce only on scripts this allows, so a page
     * moved onto {@code 'strict-dynamic'} never runs a script the allowlist policy blocked.
     *
     * @param pluginHosts the {@link CspHostContributor} hosts (may be empty)
     */
    public CspScriptAllowlist scriptAllowlist(List<String> pluginHosts) {
        String configured = scriptSrc == null ? "" : scriptSrc.trim();
        return CspScriptAllowlist.parse(configured + suffix(scriptFrameHosts(pluginHosts)));
    }

    /** Operator {@link #extraHosts} + {@code pluginHosts}, deduped, as one space-separated run. */
    private String scriptFrameHosts(List<String> pluginHosts) {
        List<String> combined = new ArrayList<>();
        if (extraHosts != null) {
            combined.addAll(extraHosts);
        }
        if (pluginHosts != null) {
            combined.addAll(pluginHosts);
        }
        return normalizedHosts(combined);
    }

    /** The {@code Reporting-Endpoints} header value pairing {@link #REPORT_GROUP} with {@link #reportUri}, or {@code null}. */
    public String reportingEndpointsHeader() {
        if (reportUri == null || reportUri.isBlank()) {
            return null;
        }
        return REPORT_GROUP + "=\"" + reportUri.trim() + "\"";
    }

    /** Adds {@code name value[suffix]} to {@code out} when {@code value} is non-blank. */
    private static void directive(List<String> out, String name, String value, String suffix) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.add(name + " " + value.trim() + suffix);
    }

    /** {@code suffix} followed by those of {@code extra} it does not already hold. */
    private static String plus(String suffix, List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return suffix;
        }
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(
                java.util.Arrays.asList(suffix.trim().isEmpty() ? new String[0] : suffix.trim().split(" ")));
        all.addAll(extra);
        return " " + String.join(" ", all);
    }

    /** A leading-space host suffix ({@code " a b"}), or empty when there are no hosts. */
    private static String suffix(String hosts) {
        return hosts.isEmpty() ? "" : " " + hosts;
    }

    /** {@code hosts} as a single space-separated token run — blanks removed, duplicates dropped, order preserved. */
    private static String normalizedHosts(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String host : hosts) {
            if (host != null && !host.isBlank()) {
                seen.add(host.trim());
            }
        }
        return String.join(" ", seen);
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = (mode != null ? mode : Mode.OFF);
    }

    /** Lenient string binder so {@code mode: report} (any case) binds, and an unknown value is treated as OFF. */
    public void setMode(String mode) {
        if (mode == null || mode.isBlank()) {
            this.mode = Mode.REPORT;
            return;
        }
        try {
            this.mode = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            this.mode = Mode.OFF;
        }
    }

    public String getDefaultSrc() {
        return defaultSrc;
    }

    public void setDefaultSrc(String defaultSrc) {
        this.defaultSrc = defaultSrc;
    }

    public String getScriptSrc() {
        return scriptSrc;
    }

    public void setScriptSrc(String scriptSrc) {
        this.scriptSrc = scriptSrc;
    }

    public String getStyleSrc() {
        return styleSrc;
    }

    public void setStyleSrc(String styleSrc) {
        this.styleSrc = styleSrc;
    }

    public String getImgSrc() {
        return imgSrc;
    }

    public void setImgSrc(String imgSrc) {
        this.imgSrc = imgSrc;
    }

    public String getFontSrc() {
        return fontSrc;
    }

    public void setFontSrc(String fontSrc) {
        this.fontSrc = fontSrc;
    }

    public String getConnectSrc() {
        return connectSrc;
    }

    public void setConnectSrc(String connectSrc) {
        this.connectSrc = connectSrc;
    }

    public String getBaseUri() {
        return baseUri;
    }

    public void setBaseUri(String baseUri) {
        this.baseUri = baseUri;
    }

    public String getFormAction() {
        return formAction;
    }

    public void setFormAction(String formAction) {
        this.formAction = formAction;
    }

    public String getFrameAncestors() {
        return frameAncestors;
    }

    public void setFrameAncestors(String frameAncestors) {
        this.frameAncestors = frameAncestors;
    }

    public String getReportUri() {
        return reportUri;
    }

    public void setReportUri(String reportUri) {
        this.reportUri = reportUri;
    }

    public List<String> getExtraHosts() {
        return extraHosts;
    }

    public void setExtraHosts(List<String> extraHosts) {
        this.extraHosts = extraHosts;
    }

    public boolean isUpgradeInsecureRequests() {
        return upgradeInsecureRequests;
    }

    public void setUpgradeInsecureRequests(boolean upgradeInsecureRequests) {
        this.upgradeInsecureRequests = upgradeInsecureRequests;
    }

    public boolean isStrictDynamic() {
        return strictDynamic;
    }

    public void setStrictDynamic(boolean strictDynamic) {
        this.strictDynamic = strictDynamic;
    }

    public boolean isPublicStrictDynamic() {
        return publicStrictDynamic;
    }

    public void setPublicStrictDynamic(boolean publicStrictDynamic) {
        this.publicStrictDynamic = publicStrictDynamic;
    }

    /**
     * Whether public pages get the derived nonce + {@code 'strict-dynamic'}: a policy is sent at all (not
     * {@link Mode#OFF}), the estate-wide {@link #strictDynamic} rollback switch is on, and
     * {@link #publicStrictDynamic} is on. Any one off ⇒ public pages keep the host-allowlist policy.
     */
    public boolean isPublicNonceActive() {
        return mode != Mode.OFF && strictDynamic && publicStrictDynamic;
    }
}
