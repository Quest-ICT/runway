package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The host-allowlist {@code script-src} evaluated as a browser evaluates it (CSP Level 3 §6.7.2) — the question the
 * public-page edge asks before stamping a nonce: <em>did the allowlist policy run this script?</em> A "yes" that a
 * browser would answer "no" re-opens the hole the allowlist closes, so the negative cases outnumber the positive ones.
 */
class CspScriptAllowlistTest {

    private static final URI HTTP_DOC = URI.create("http://site.test/a/b");
    private static final URI HTTPS_DOC = URI.create("https://site.test/a/b");

    private static boolean allows(String sourceList, String url) {
        return CspScriptAllowlist.parse(sourceList).allowsUrl(url, HTTP_DOC, HTTP_DOC);
    }

    @Test
    void selfIsTheDocumentOriginPlusTheSecureUpgrade() {
        assertThat(allows("'self'", "/x.js")).isTrue();
        assertThat(allows("'self'", "x.js")).isTrue();
        assertThat(allows("'self'", "http://site.test/x.js")).isTrue();
        assertThat(allows("'self'", "http://site.test:80/x.js")).isTrue();
        assertThat(allows("'self'", "https://site.test/x.js")).as("http page → https same host").isTrue();
        assertThat(allows("'self'", "http://site.test:8080/x.js")).isFalse();
        assertThat(allows("'self'", "http://other.test/x.js")).isFalse();
        assertThat(allows("'self'", "http://sub.site.test/x.js")).isFalse();
        CspScriptAllowlist self = CspScriptAllowlist.selfOnly();
        assertThat(self.allowsUrl("http://site.test/x.js", HTTPS_DOC, HTTPS_DOC)).as("no downgrade").isFalse();
        URI devDoc = URI.create("http://localhost:8080/");
        assertThat(self.allowsUrl("/x.js", devDoc, devDoc)).isTrue();
        assertThat(self.allowsUrl("http://localhost:8080/x.js", devDoc, devDoc)).isTrue();
        assertThat(self.allowsUrl("http://localhost/x.js", devDoc, devDoc)).isFalse();
    }

    @Test
    void anEmptyOrNoneListAllowsNothing() {
        assertThat(allows("", "/x.js")).isFalse();
        assertThat(allows("'none'", "/x.js")).isFalse();
        assertThat(CspScriptAllowlist.parse(null).allowsUrl("/x.js", HTTP_DOC, HTTP_DOC)).isFalse();
    }

    @Test
    void hostSourcesMatchSchemeHostPortAndPath() {
        String google = "https://www.google.com";
        assertThat(allows(google, "https://www.google.com/recaptcha/api.js")).isTrue();
        assertThat(allows(google, "https://WWW.Google.COM/x.js")).isTrue();
        assertThat(allows(google, "http://www.google.com/x.js")).as("no downgrade").isFalse();
        assertThat(allows(google, "https://www.google.com:444/x.js")).isFalse();
        assertThat(allows(google, "https://google.com/x.js")).isFalse();
        assertThat(allows(google, "https://www.google.com.evil.test/x.js")).isFalse();

        assertThat(allows("http://cdn.test", "https://cdn.test/x.js")).as("http source → https url").isTrue();
        assertThat(allows("cdn.test", "http://cdn.test/x.js")).as("no scheme: the document's").isTrue();
        assertThat(allows("cdn.test", "https://cdn.test/x.js")).as("…or its secure upgrade").isTrue();
        assertThat(CspScriptAllowlist.parse("cdn.test").allowsUrl("http://cdn.test/x.js", HTTPS_DOC, HTTPS_DOC))
                .as("no scheme on an https page: no http").isFalse();

        assertThat(allows("https://*.example.com", "https://a.example.com/x.js")).isTrue();
        assertThat(allows("https://*.example.com", "https://a.b.example.com/x.js")).isTrue();
        assertThat(allows("https://*.example.com", "https://example.com/x.js")).as("subdomains only").isFalse();
        assertThat(allows("https://*.example.com", "https://badexample.com/x.js")).isFalse();

        assertThat(allows("https://cdn.test:8443", "https://cdn.test:8443/x.js")).isTrue();
        assertThat(allows("https://cdn.test:8443", "https://cdn.test/x.js")).isFalse();
        assertThat(allows("https://cdn.test:*", "https://cdn.test:9/x.js")).isTrue();
        assertThat(allows("https://cdn.test:443", "https://cdn.test/x.js")).as("explicit default port").isTrue();

        assertThat(allows("https://cdn.test/lib/", "https://cdn.test/lib/a.js")).isTrue();
        assertThat(allows("https://cdn.test/lib/", "https://cdn.test/lib/sub/a.js")).isTrue();
        assertThat(allows("https://cdn.test/lib/", "https://cdn.test/other.js")).isFalse();
        assertThat(allows("https://cdn.test/lib/", "https://cdn.test/lib/../evil.js")).as("dot segments").isFalse();
        assertThat(allows("https://cdn.test/lib/", "https://cdn.test/lib/%2e%2e/evil.js")).as("%2e").isFalse();
        assertThat(allows("https://cdn.test/lib/a.js", "https://cdn.test/lib/a.js?v=1")).isTrue();
        assertThat(allows("https://cdn.test/lib/a.js", "https://cdn.test/lib/a.jsx")).as("exact path").isFalse();
        assertThat(allows("https://cdn.test/Lib/", "https://cdn.test/lib/a.js")).as("paths keep their case")
                .isFalse();
        assertThat(allows("https://cdn.test/l%69b/", "https://cdn.test/lib/a.js")).as("compared decoded").isTrue();
    }

    @Test
    void wildcardAndSchemeSources() {
        assertThat(allows("*", "https://anything.test/x.js")).isTrue();
        assertThat(allows("*", "data:text/javascript,1")).as("* never matches data:").isFalse();
        assertThat(allows("https:", "https://anything.test/x.js")).isTrue();
        assertThat(allows("https:", "http://anything.test/x.js")).isFalse();
        assertThat(allows("http:", "https://anything.test/x.js")).as("scheme upgrade").isTrue();
        assertThat(allows("data:", "data:text/javascript,1")).isTrue();
        assertThat(allows("'self'", "data:text/javascript,1")).isFalse();
    }

    /** Unclassifiable URLs are refused — a browser could read them differently from the JDK. */
    @Test
    void failsClosedOnUrlsItCannotPinDown() {
        for (String url : new String[] {"\\\\evil.test/x.js", "/\\evil.test/x.js", "https:evil.test/x.js",
                "https:///evil.test/x.js", "https://user@site.test/x.js", "https://%73ite.test/x.js",
                "/a/%zz.js", "/a\u0001.js", "", "   ", null}) {
            assertThat(allows("'self' https://evil.test", url)).as(String.valueOf(url)).isFalse();
        }
    }

    /**
     * Proven in Chrome 155: where the authority starts, the WHATWG URL parser skips any number of
     * slashes, so {@code ///evil.example/x.js} on an http(s) page loads from evil.example; {@code java.net.URI}
     * (RFC 3986) reads an empty authority and a same-origin path. Every such spelling is refused — even one naming
     * the page's own host.
     */
    @Test
    void threeOrMoreSlashesWhereTheAuthorityStartsAreRefused() {
        for (String url : new String[] {"///evil.example/x.js", "////evil.example/x.js?q2", "/////evil.example/x.js",
                "///site.test/x.js", "///x.js", "https:////evil.example/x.js", "http:///site.test/x.js",
                "HTTPS:////site.test/x.js"}) {
            assertThat(allows("'self'", url)).as(url).isFalse();
            assertThat(allows("*", url)).as("* too: " + url).isFalse();
        }
        assertThat(CspScriptAllowlist.sameOriginBase("///evil.example/", HTTP_DOC)).as("a <base> spelled so").isNull();
        // Two slashes stay what both parsers agree on; one slash is a path; more slashes later in the path are a path.
        assertThat(allows("'self'", "//site.test/x.js")).isTrue();
        assertThat(allows("'self'", "//evil.example/x.js")).isFalse();
        assertThat(allows("'self'", "/x//y.js")).isTrue();
        assertThat(allows("'self'", "./x///y.js")).isTrue();
        assertThat(allows("'self'", "http://site.test///x.js")).as("extra slashes in the PATH are a path").isTrue();
    }

    @Test
    void aRelativeUrlNeedsABase() {
        CspScriptAllowlist self = CspScriptAllowlist.selfOnly();
        assertThat(self.allowsUrl("x.js", null, HTTP_DOC)).isFalse();
        assertThat(self.allowsUrl("//site.test/x.js", null, HTTP_DOC)).isFalse();
        assertThat(self.allowsUrl("http://site.test/x.js", null, HTTP_DOC)).as("absolute needs none").isTrue();
        assertThat(self.allowsUrl("/x.js", HTTP_DOC, null)).as("no document: nothing is same-origin").isFalse();
    }

    @Test
    void sameOriginBaseFollowsOnlyTheDocumentOrigin() {
        assertThat(CspScriptAllowlist.sameOriginBase("/sub/", HTTP_DOC)).isEqualTo(URI.create("http://site.test/sub/"));
        assertThat(CspScriptAllowlist.sameOriginBase("http://site.test/x/", HTTP_DOC)).isNotNull();
        assertThat(CspScriptAllowlist.sameOriginBase("https://site.test/x/", HTTP_DOC)).as("other scheme").isNull();
        assertThat(CspScriptAllowlist.sameOriginBase("//evil.test/", HTTP_DOC)).isNull();
        assertThat(CspScriptAllowlist.sameOriginBase("\\\\evil.test/", HTTP_DOC)).isNull();
        assertThat(CspScriptAllowlist.sameOriginBase(null, HTTP_DOC)).isNull();
    }

    @Test
    void inlineIsAllowedOnlyByAnEffectiveUnsafeInline() {
        assertThat(CspScriptAllowlist.selfOnly().allowsInline()).isFalse();
        assertThat(CspScriptAllowlist.parse("'self' 'unsafe-inline'").allowsInline()).isTrue();
        assertThat(CspScriptAllowlist.parse("'self' 'UNSAFE-INLINE'").allowsInline()).isTrue();
        assertThat(CspScriptAllowlist.parse("'unsafe-inline' 'sha256-abc='").allowsInline()).isFalse();
        assertThat(CspScriptAllowlist.parse("'unsafe-inline' 'nonce-abc'").allowsInline()).isFalse();
        assertThat(CspScriptAllowlist.parse("'unsafe-inline' 'strict-dynamic'").allowsInline()).isFalse();
    }

    /** A configured 'strict-dynamic' made the old (nonce-less) public policy ignore hosts and 'self' entirely. */
    @Test
    void aConfiguredStrictDynamicAllowedNothingByHost() {
        assertThat(allows("'strict-dynamic' 'self' https://cdn.test", "/x.js")).isFalse();
        assertThat(allows("'strict-dynamic' 'self' https://cdn.test", "https://cdn.test/x.js")).isFalse();
    }

    @Test
    void malformedSourceExpressionsAreIgnoredLikeABrowserDoes() {
        assertThat(allows("https://cdn.test/x.js?v=1", "https://cdn.test/x.js?v=1")).as("no query in a source")
                .isFalse();
        assertThat(allows("https://*example.com", "https://evilexample.com/x.js")).isFalse();
        assertThat(allows("https://ex*.com 'self'", "/x.js")).isTrue();
    }

    @Test
    void theFingerprintIdentifiesTheListAndNeverChangesForTheSameOne() {
        String a = CspScriptAllowlist.parse("'self'  https://a.test").fingerprint();
        assertThat(a).isEqualTo(CspScriptAllowlist.parse("'self' https://a.test").fingerprint()).hasSize(12);
        assertThat(a).isNotEqualTo(CspScriptAllowlist.parse("'self' https://b.test").fingerprint());
    }

    /** The allowlist is built from exactly the tokens the policy carries after the nonce. */
    @Test
    void thePropertiesBuildTheSameListThePolicyCarries() {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setExtraHosts(List.of("https://cdn.test", "https://www.google.com"));
        CspScriptAllowlist allowlist = props.scriptAllowlist(List.of("https://www.google.com", "https://www.gstatic.com"));
        assertThat(allowlist.sourceList())
                .isEqualTo("'self' https://cdn.test https://www.google.com https://www.gstatic.com");
        String policy = props.buildPolicy(List.of("https://www.google.com", "https://www.gstatic.com"));
        assertThat(policy).contains("script-src " + RunwayCspProperties.NONCE_PLACEHOLDER
                + RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER + allowlist.sourceList() + ";");
        assertThat(new RunwayCspProperties().scriptAllowlist(List.of()).sourceList()).isEqualTo("'self'");
    }
}
