package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bound {@code runway.csp.*} properties: the report-only default, the self-only default policy string (every
 * expected directive, {@code script-src} WITHOUT {@code 'unsafe-inline'}, {@code style-src} WITH it,
 * {@code object-src 'none'}, {@code report-uri}+{@code report-to}), the header-name-by-mode mapping, how operator
 * {@code extra-hosts} widen the full fetch surface while plugin hosts are scoped to script/connect/frame,
 * and the opt-in {@code upgrade-insecure-requests}.
 */
class RunwayCspPropertiesTest {

    /** The script-src lead: the nonce placeholder + the strict-dynamic placeholder (both filter-substituted). With
     *  strict-dynamic on (the default) buildPolicy carries both, in this order, before 'self'. */
    private static final String NONCE =
            RunwayCspProperties.NONCE_PLACEHOLDER + RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER;

    @Test
    void defaultModeIsReportOnly() {
        RunwayCspProperties props = new RunwayCspProperties();
        assertThat(props.getMode()).isEqualTo(RunwayCspProperties.Mode.REPORT);
        assertThat(props.headerName()).isEqualTo("Content-Security-Policy-Report-Only");
    }

    @Test
    void headerNameFollowsMode() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setMode(RunwayCspProperties.Mode.ENFORCE);
        assertThat(props.headerName()).isEqualTo("Content-Security-Policy");
        props.setMode(RunwayCspProperties.Mode.OFF);
        assertThat(props.headerName()).isNull();
    }

    @Test
    void stringModeBinderIsLenientAndCaseInsensitive() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setMode("enforce");
        assertThat(props.getMode()).isEqualTo(RunwayCspProperties.Mode.ENFORCE);
        props.setMode("REPORT");
        assertThat(props.getMode()).isEqualTo(RunwayCspProperties.Mode.REPORT);
        props.setMode("gibberish"); // unknown value fails safe to OFF (no header) rather than throwing
        assertThat(props.getMode()).isEqualTo(RunwayCspProperties.Mode.OFF);
        props.setMode("");           // blank restores the report-only default
        assertThat(props.getMode()).isEqualTo(RunwayCspProperties.Mode.REPORT);
    }

    @Test
    void defaultPolicyIsSelfOnlyWithExpectedDirectives() {
        String policy = new RunwayCspProperties().buildPolicy();
        assertThat(policy).contains("default-src 'self'");
        assertThat(policy).contains("script-src " + NONCE + "'self';"); // nonce + SD placeholder + 'self'
        assertThat(policy).doesNotContain(NONCE + "'self' 'unsafe-inline'"); // no 'unsafe-inline' on script-src
        // The LITERAL 'strict-dynamic' is never baked into the template — CspFilter substitutes the placeholder to
        // " 'strict-dynamic'" per (kernel) surface and to "" elsewhere.
        assertThat(policy).doesNotContain("'strict-dynamic'");
        assertThat(policy).contains("style-src 'self' 'unsafe-inline'"); // kept: inline style= attributes remain
        assertThat(policy).contains("img-src 'self' data:");
        assertThat(policy).contains("font-src 'self'");
        assertThat(policy).contains("connect-src 'self'");
        assertThat(policy).contains("object-src 'none'"); // CSP-D
        assertThat(policy).contains("base-uri 'self'");
        assertThat(policy).contains("form-action 'self'");
        assertThat(policy).contains("frame-ancestors 'self'");
        assertThat(policy).contains("report-uri /__csp-report");
        assertThat(policy).endsWith("report-to csp-endpoint"); // CSP-C, emitted last
    }

    @Test
    void scriptSrcCarriesExactlyOneNonceAndOneStrictDynamicPlaceholder() {
        String policy = new RunwayCspProperties().buildPolicy(); // strict-dynamic on by default
        // Both placeholders lead script-src, in order, substituted per response by CspFilter; each appears once.
        assertThat(policy).contains("script-src " + NONCE + "'self'");
        assertThat(policy.split(java.util.regex.Pattern.quote(RunwayCspProperties.NONCE_PLACEHOLDER), -1).length - 1)
                .as("the nonce placeholder occurs exactly once").isEqualTo(1);
        assertThat(policy.split(java.util.regex.Pattern.quote(RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER), -1).length - 1)
                .as("the strict-dynamic placeholder occurs exactly once").isEqualTo(1);
        // Both placeholders are printable + brace-delimited so a base64url nonce can never collide with them.
        assertThat(RunwayCspProperties.NONCE_PLACEHOLDER).startsWith("{").endsWith("}");
        assertThat(RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER).startsWith("{").endsWith("}");
    }

    @Test
    void disablingStrictDynamicDropsItsPlaceholderLeavingNonceOnly() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setStrictDynamic(false);
        String policy = props.buildPolicy();
        // Nonce-only behaviour: nonce + 'self' + host-allowlist, no strict-dynamic placeholder anywhere.
        assertThat(policy).contains("script-src " + RunwayCspProperties.NONCE_PLACEHOLDER + "'self'");
        assertThat(policy).doesNotContain(RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER);
    }

    @Test
    void defaultPolicyHasNoThirdPartyHostNoFrameSrcNoUpgrade() {
        String policy = new RunwayCspProperties().buildPolicy();
        // Runway self-hosts its vendor assets, so the default policy references no external origin.
        assertThat(policy).doesNotContain("https://");
        assertThat(policy).doesNotContain("cdn.jsdelivr.net");
        // frame-src is only emitted when there are extra hosts to frame; by default frames fall back to default-src.
        assertThat(policy).doesNotContain("frame-src");
        // upgrade-insecure-requests is opt-in, off by default.
        assertThat(policy).doesNotContain("upgrade-insecure-requests");
    }

    @Test
    void reportingEndpointsHeaderPairsTheGroupWithTheReportUri() {
        RunwayCspProperties props = new RunwayCspProperties();
        assertThat(props.reportingEndpointsHeader()).isEqualTo("csp-endpoint=\"/__csp-report\"");
        props.setReportUri("");
        assertThat(props.reportingEndpointsHeader()).isNull(); // no report-uri → no Reporting-Endpoints
    }

    @Test
    void upgradeInsecureRequestsIsAppendedWhenEnabled() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setUpgradeInsecureRequests(true);
        assertThat(props.buildPolicy()).contains("upgrade-insecure-requests");
    }

    @Test
    void operatorExtraHostsWidenTheFullFetchSurfaceAndEnableFrameSrc() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setExtraHosts(List.of("https://cdn.example.com"));
        String policy = props.buildPolicy();
        assertThat(policy).contains("default-src 'self' https://cdn.example.com");
        assertThat(policy).contains("script-src " + NONCE + "'self' https://cdn.example.com");
        assertThat(policy).contains("style-src 'self' 'unsafe-inline' https://cdn.example.com");
        assertThat(policy).contains("img-src 'self' data: https://cdn.example.com");
        assertThat(policy).contains("font-src 'self' https://cdn.example.com");
        assertThat(policy).contains("connect-src 'self' https://cdn.example.com");
        assertThat(policy).contains("frame-src 'self' https://cdn.example.com");
        // A fetch/frame concern only — never base-uri/form-action/object-src.
        assertThat(policy).contains("base-uri 'self';");
        assertThat(policy).contains("form-action 'self';");
        assertThat(policy).contains("object-src 'none';");
        assertThat(policy).doesNotContain("base-uri 'self' https://");
        assertThat(policy).doesNotContain("object-src 'none' https://");
    }

    @Test
    void pluginHostsAreScopedToScriptConnectFrameOnly() {
        // A plugin (e.g. protection, for the CAPTCHA providers) contributes hosts via buildPolicy(pluginHosts).
        // Unlike operator extra-hosts, these widen ONLY the directives a widget needs — script/connect/frame — never
        // style/img/font/default. Here there are NO operator extra-hosts, so the plugin host is the only external one.
        RunwayCspProperties props = new RunwayCspProperties();
        String policy = props.buildPolicy(List.of("https://challenges.cloudflare.com"));
        assertThat(policy).contains("script-src " + NONCE + "'self' https://challenges.cloudflare.com");
        assertThat(policy).contains("connect-src 'self' https://challenges.cloudflare.com");
        assertThat(policy).contains("frame-src 'self' https://challenges.cloudflare.com");
        // NOT widened by a plugin host:
        assertThat(policy).contains("default-src 'self';");
        assertThat(policy).contains("style-src 'self' 'unsafe-inline';");
        assertThat(policy).contains("img-src 'self' data:;");
        assertThat(policy).contains("font-src 'self';");
        assertThat(policy).doesNotContain("default-src 'self' https://");
        assertThat(policy).doesNotContain("style-src 'self' 'unsafe-inline' https://");
        assertThat(policy).doesNotContain("font-src 'self' https://");
    }

    @Test
    void operatorAndPluginHostsMergeDedupedOnTheSharedDirectives() {
        // An operator host and a plugin host, one overlapping. The shared directives (script/connect/frame) carry the
        // deduped union; a purely-operator host still reaches the full fetch surface.
        RunwayCspProperties props = new RunwayCspProperties();
        props.setExtraHosts(List.of("https://cdn.example.com", "https://www.google.com")); // google overlaps a plugin host
        String policy = props.buildPolicy(List.of("https://www.google.com", "https://challenges.cloudflare.com"));

        // script-src: operator + plugin, deduped, extra-hosts order first then the new plugin host.
        assertThat(policy).contains("script-src " + NONCE + "'self' "
                + "https://cdn.example.com https://www.google.com https://challenges.cloudflare.com");
        // The plugin-only host reaches script/connect/frame but NOT font-src (a fetch directive plugins don't widen).
        assertThat(policy).doesNotContain("font-src 'self' https://challenges.cloudflare.com");
        // www.google.com is in BOTH sets, so it appears on all 7 widened directives (6 fetch + frame-src); deduped once each.
        assertThat(policy.split("https://www.google.com", -1).length - 1)
                .as("www.google.com once per widened directive (6 fetch + frame-src)")
                .isEqualTo(7);
        // cdn.example.com is operator-only → the full fetch surface (6) + frame-src = 7 as well.
        assertThat(policy.split("https://cdn.example.com", -1).length - 1).isEqualTo(7);
        // challenges.cloudflare.com is plugin-only → script + connect + frame = 3.
        assertThat(policy.split("https://challenges.cloudflare.com", -1).length - 1)
                .as("plugin-only host on script/connect/frame only")
                .isEqualTo(3);
    }

    @Test
    void buildPolicyWithNoPluginHostsEqualsTheDefault() {
        RunwayCspProperties props = new RunwayCspProperties();
        assertThat(props.buildPolicy(List.of())).isEqualTo(props.buildPolicy());
    }

    @Test
    void blankReportUriDropsBothReportDirectivesCleanly() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setReportUri("");
        String policy = props.buildPolicy();
        assertThat(policy).doesNotContain("report-uri");
        assertThat(policy).doesNotContain("report-to"); // report-to is gated on the same report-uri
        assertThat(policy).doesNotEndWith("; "); // no trailing separator (String.join)
        assertThat(policy).endsWith("frame-ancestors 'self'");
    }

    @Test
    void aSitesAdditionsJoinOnlyTheirDirectivesAndNeverScripts() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setExtraHosts(List.of("https://cdn.platform.test"));
        String policy = props.buildPolicy(List.of(), java.util.Map.of(
                "img-src", List.of("https://img.site.test", "https://cdn.platform.test"),
                "frame-src", List.of("https://player.site.test"),
                "media-src", List.of("https://media.site.test")));
        // appended once, after the platform's own hosts
        assertThat(policy).contains("img-src 'self' data: https://cdn.platform.test https://img.site.test;");
        assertThat(policy).contains("frame-src 'self' https://cdn.platform.test https://player.site.test");
        assertThat(policy).contains("media-src 'self' https://cdn.platform.test https://media.site.test");
        assertThat(policy).doesNotContainPattern("script-src[^;]*site\\.test");
        assertThat(props.buildPolicy(List.of(), java.util.Map.of())).isEqualTo(props.buildPolicy(List.of()));
    }
}
