package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import org.junit.jupiter.api.Test;

/**
 * The plugin-owned {@code /robots.txt}: a general allow-all group + the {@code Sitemap:} line, with a
 * configurable AI/LLM-crawler policy (per-site {@code robots.ai-policy} over the app-level {@code robots.ai-default}),
 * tenant extra Disallow paths, and a full-body custom override.
 */
class RobotsTxtEndpointTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");

    private static PluginSettings config(Map<String, Object> cfg) {
        return TestSettings.declared().site("acme", cfg);
    }

    private static DiscoverabilityProperties props(String aiDefault) {
        DiscoverabilityProperties p = new DiscoverabilityProperties();
        p.getRobots().setAiDefault(aiDefault);
        return p;
    }

    private static RouteRequest req(SiteView site, Map<String, String> headers) {
        return req(site, headers, "");
    }

    /** A request as the kernel builds it: the trusted origin, no forwarded headers. */
    private static RouteRequest req(SiteView site, Map<String, String> headers, String origin) {
        return new RouteRequest(site, EN, "GET", "/robots.txt", Map.of(), headers, new byte[0], Map.of(), null,
                origin);
    }

    private static String bodyOf(Map<String, Object> cfg, String aiDefault, SiteView site, Map<String, String> headers) {
        return bodyOf(cfg, aiDefault, req(site, headers));
    }

    private static String bodyOf(Map<String, Object> cfg, String aiDefault, RouteRequest req) {
        RouteResponse r = new RobotsTxtEndpoint(config(cfg), props(aiDefault)).handle(req);
        assertEquals(200, r.status());
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void defaultAllowsEveryoneAndPointsAtTheSitemap() {
        String out = bodyOf(Map.of(), "allow", SITE, Map.of());
        assertTrue(out.contains("User-agent: *"), out);
        assertTrue(out.contains("Allow: /"), out);
        assertTrue(out.contains("Sitemap: https://acme.test/sitemap.xml"), out);
        // The AI/LLM posture is stated EXPLICITLY: a named group is emitted even on allow, with Allow — not
        // Disallow — so nothing is actually blocked.
        assertTrue(out.contains("User-agent: GPTBot"), "explicit AI-crawler group even on allow: " + out);
        assertFalse(out.contains("Disallow: /"), "allow posture disallows nothing: " + out);
    }

    @Test
    void perSiteDisallowEmitsAiCrawlerGroups() {
        String out = bodyOf(Map.of("robots.ai-policy", "disallow"), "allow", SITE, Map.of());
        assertTrue(out.contains("User-agent: GPTBot"), out);
        assertTrue(out.contains("User-agent: ClaudeBot"), out);
        assertTrue(out.contains("User-agent: Google-Extended"), out);
        assertTrue(out.contains("Disallow: /"), out);
        assertTrue(out.contains("User-agent: *"), "classic crawlers are still allowed");
    }

    @Test
    void inheritsAppLevelDisallowDefault() {
        String out = bodyOf(Map.of(), "disallow", SITE, Map.of()); // policy defaults to inherit
        assertTrue(out.contains("User-agent: GPTBot"), "inherit → app default disallow: " + out);
    }

    @Test
    void perSiteAllowOverridesAppLevelDisallowDefault() {
        String out = bodyOf(Map.of("robots.ai-policy", "allow"), "disallow", SITE, Map.of());
        // Per-site allow wins over the app default: the named group is still present (explicit posture) but as
        // Allow, never Disallow: /.
        assertTrue(out.contains("User-agent: GPTBot"), out);
        assertFalse(out.contains("Disallow: /"), "per-site allow wins over the app default: " + out);
    }

    @Test
    void allowedAiGroupMirrorsTenantExtraDisallows() {
        // On the allow posture the named AI group MIRRORS the general group's exclusions, so a most-specific
        // robots.txt match cannot let AI crawlers bypass the tenant's own Disallow paths. /private thus appears
        // twice: once under the AI group, once under the * group.
        String out = bodyOf(Map.of("robots.extra-disallow", List.of("/private")), "allow", SITE, Map.of());
        long count = out.lines().filter(l -> l.equals("Disallow: /private")).count();
        assertEquals(2, count, "AI group mirrors the tenant exclusion: " + out);
    }

    @Test
    void omitsSitemapLineWhenSitemapDisabled() {
        String out = bodyOf(Map.of("sitemap.enabled", false), "allow", SITE, Map.of());
        assertFalse(out.contains("Sitemap:"), out);
        assertTrue(out.contains("Allow: /"), "still serves the allow group");
    }

    @Test
    void emitsTenantExtraDisallowPathsNormalized() {
        String out = bodyOf(Map.of("robots.extra-disallow", List.of("/private", "search")), "allow", SITE, Map.of());
        assertTrue(out.contains("Disallow: /private"), out);
        assertTrue(out.contains("Disallow: /search"), "a bare path is normalized to /search: " + out);
    }

    @Test
    void customBodyReplacesEverythingVerbatim() {
        String out = bodyOf(Map.of("robots.custom", "User-agent: *\nDisallow: /"), "allow", SITE, Map.of());
        assertEquals("User-agent: *\nDisallow: /\n", out);
        assertFalse(out.contains("Sitemap:"), "the custom body is verbatim — no generated additions");
    }

    @Test
    void takesTheSitemapOriginFromTheTrustedRequestOriginWhenBaseUrlUnset() {
        SiteView noBase = new SiteView("acme", "Acme", EN, List.of(EN), null);
        String out = bodyOf(Map.of(), "allow", req(noBase, Map.of("Host", "acme.test"), "http://acme.test:8080"));
        assertTrue(out.contains("Sitemap: http://acme.test:8080/sitemap.xml"), "the origin as the kernel gated it, "
                + "scheme and port included — never a guessed https from the Host header: " + out);
    }

    @Test
    void theConfiguredBaseUrlWinsOverTheRequestOrigin() {
        String out = bodyOf(Map.of(), "allow", req(SITE, Map.of(), "http://internal:8080"));
        assertTrue(out.contains("Sitemap: https://acme.test/sitemap.xml"), out);
    }

    @Test
    void noBaseUrlAndNoOriginMeansNoSitemapLineRatherThanARelativeOne() {
        SiteView noBase = new SiteView("acme", "Acme", EN, List.of(EN), null);
        String out = bodyOf(Map.of(), "allow", req(noBase, Map.of("Host", "acme.test"), ""));
        assertFalse(out.contains("Sitemap:"), "a Host header alone is no longer trusted: " + out);
    }

    /**
     * The exact regression the SEO/GEO compliance audit found in 1.3.0: {@code ClaudeBot} was listed but
     * {@code Claude-User} and {@code Claude-SearchBot} were not, so a site set to {@code disallow} was still
     * reachable by two of the three Anthropic agents — an opt-out the owner believed was complete and was not.
     *
     * <p>Asserted per-vendor across all three CLASSES the article distinguishes (training / live retrieval /
     * user-triggered fetch), because the failure mode is one missing name, not a missing feature — a test that
     * only checked "some AI agent is present" would have passed against the bug.
     */
    @Test
    void everyAgentClassIsCoveredForEachMajorVendor() {
        String out = bodyOf(Map.of("robots.ai-policy", "disallow"), "allow", SITE, Map.of());
        for (String agent : List.of(
                "GPTBot", "OAI-SearchBot", "ChatGPT-User",          // OpenAI: train / retrieve / user
                "ClaudeBot", "Claude-SearchBot", "Claude-User",     // Anthropic: train / retrieve / user
                "PerplexityBot", "Perplexity-User",                 // Perplexity: retrieve / user
                "Google-Extended", "Applebot-Extended")) {          // training opt-outs, NOT the search bots
            assertTrue(out.contains("User-agent: " + agent + "\n"), "missing agent: " + agent);
        }
        // The training opt-out must never take the SEARCH crawler with it — that would deindex the site.
        assertFalse(out.contains("User-agent: Googlebot\n"), "Googlebot must never be in the AI opt-out group");
        assertFalse(out.contains("User-agent: Applebot\n"), "Applebot must never be in the AI opt-out group");
    }

    /**
     * The operator escape hatch: the built-in list is a compile-time constant that goes stale silently, so
     * {@code robots.ai-agents-extra} must be able to cover a newly announced agent without a plugin release.
     *
     * <p>Also pins the injection guard. An operator-supplied name lands in a {@code User-agent:} line, so a value
     * carrying CR/LF or a colon could forge extra robots directives into every tenant's file — the same class of
     * hole already closed for tenant exclusion paths.
     */
    @Test
    void operatorAddedAgentsAreAppendedDedupedAndSanitised() {
        DiscoverabilityProperties p = props("allow");
        p.getRobots().setAiAgentsExtra(List.of(
                "BrandNewBot",                       // the point of the feature
                "gptbot",                            // already built in, different case → must not duplicate
                "Evil\nUser-agent: *\nDisallow: /",  // injection attempt via CR/LF
                "  Spaced Bot  "));                  // whitespace stripped
        RouteResponse r = new RobotsTxtEndpoint(config(Map.of("robots.ai-policy", "disallow")), p)
                .handle(req(SITE, Map.of()));
        String out = new String(r.body(), StandardCharsets.UTF_8);

        assertTrue(out.contains("User-agent: BrandNewBot\n"), "operator addition must be emitted");
        assertEquals(1, out.split("(?i)User-agent: GPTBot\n", -1).length - 1, "must not duplicate a built-in");
        assertTrue(out.contains("User-agent: SpacedBot\n"), "whitespace stripped: " + out);
        // The injected directive must not survive as its own line.
        assertFalse(out.contains("\nDisallow: /\nUser-agent: *"), "CR/LF injection must be neutralised");
        assertTrue(out.contains("User-agent: EvilUser-agent:*Disallow:/\n")
                        || out.contains("User-agent: EvilUser-agent"),
                "the payload is flattened into one inert token: " + out);
    }

    // ---- the three AI classes are separately governable -----------------------------------------------

    /** The group a named agent falls in: the lines from its {@code User-agent:} to the next blank line. */
    private static String groupContaining(String robots, String agent) {
        for (String group : robots.split("\n\n")) {
            if (group.contains("User-agent: " + agent + "\n") || group.endsWith("User-agent: " + agent)) {
                return group;
            }
        }
        return "";
    }

    /**
     * The case the split exists for: decline training, stay citable.
     *
     * <p>Before this a site had one AI switch, so the only way to refuse training was to refuse the answer
     * engines too — which removes the site from generated answers, the opposite of what a site optimising for
     * them wants. The three classes carry opposite consequences and now carry opposite settings.
     */
    @Test
    void aSiteCanDeclineTrainingWhileStayingCitable() {
        String out = bodyOf(Map.of("robots.ai-training", "disallow", "robots.ai-retrieval", "allow"),
                "allow", SITE, Map.of());

        assertTrue(groupContaining(out, "GPTBot").contains("Disallow: /"), out);
        assertTrue(groupContaining(out, "ClaudeBot").contains("Disallow: /"), out);
        assertTrue(groupContaining(out, "OAI-SearchBot").contains("Allow: /"), out);
        assertTrue(groupContaining(out, "Claude-SearchBot").contains("Allow: /"), out);
    }

    /** A user-triggered fetch is its own decision: someone asked for this page. */
    @Test
    void theUserTriggeredClassIsGovernedSeparately() {
        String out = bodyOf(Map.of("robots.ai-policy", "disallow", "robots.ai-user", "allow"),
                "allow", SITE, Map.of());

        assertTrue(groupContaining(out, "ChatGPT-User").contains("Allow: /"), out);
        assertTrue(groupContaining(out, "Claude-User").contains("Allow: /"), out);
        assertTrue(groupContaining(out, "GPTBot").contains("Disallow: /"), "training still declined: " + out);
    }

    /**
     * A site that sets no per-class override gets exactly the file it got before classes existed.
     *
     * <p>Byte-for-byte, agent order included. Splitting a site that expressed no such preference into three
     * identical groups would churn every crawler's copy of a file to say precisely the same thing.
     */
    @Test
    void aSiteWithNoPerClassOverrideGetsTheIdenticalSingleGroup() {
        String withoutClasses = bodyOf(Map.of("robots.ai-policy", "disallow"), "allow", SITE, Map.of());
        String allInherit = bodyOf(Map.of("robots.ai-policy", "disallow", "robots.ai-training", "inherit",
                "robots.ai-retrieval", "inherit", "robots.ai-user", "inherit"), "allow", SITE, Map.of());

        assertEquals(withoutClasses, allInherit);
        assertEquals(1, withoutClasses.split("Disallow: /\n", -1).length - 1,
                "one AI group, not three: " + withoutClasses);
    }

    /** Three overrides that happen to agree also collapse to one group — the test is the OUTPUT, not the keys. */
    @Test
    void perClassOverridesThatAgreeStillCollapseToOneGroup() {
        String out = bodyOf(Map.of("robots.ai-training", "disallow", "robots.ai-retrieval", "disallow",
                "robots.ai-user", "disallow"), "allow", SITE, Map.of());

        assertEquals(bodyOf(Map.of("robots.ai-policy", "disallow"), "allow", SITE, Map.of()), out);
    }

    /** A per-class override beats the site policy; an unset class still inherits it. */
    @Test
    void aClassOverrideBeatsTheSitePolicyAndAnUnsetClassInheritsIt() {
        String out = bodyOf(Map.of("robots.ai-policy", "allow", "robots.ai-training", "disallow"),
                "disallow", SITE, Map.of());

        assertTrue(groupContaining(out, "GPTBot").contains("Disallow: /"), "the override wins: " + out);
        assertTrue(groupContaining(out, "OAI-SearchBot").contains("Allow: /"),
                "an unset class follows the SITE policy, not the app default: " + out);
    }

    /**
     * An operator-added agent with no declared class follows the site's overall policy.
     *
     * <p>Exactly what it did before classes existed. Guessing a class for a name the plugin does not recognise
     * would apply a policy the operator never chose — and would do it silently.
     */
    @Test
    void anUnclassifiedOperatorAgentFollowsTheOverallPolicy() {
        DiscoverabilityProperties p = props("allow");
        p.getRobots().setAiAgentsExtra(List.of("BrandNewBot"));
        String out = new String(new RobotsTxtEndpoint(
                config(Map.of("robots.ai-policy", "disallow", "robots.ai-user", "allow")), p)
                .handle(req(SITE, Map.of())).body(), StandardCharsets.UTF_8);

        assertTrue(groupContaining(out, "BrandNewBot").contains("Disallow: /"), out);
        assertTrue(groupContaining(out, "Claude-User").contains("Allow: /"), out);
    }

    /** …and an operator can classify a newly-announced agent the day it is announced. */
    @Test
    void anOperatorCanDeclareTheClassOfANewAgent() {
        DiscoverabilityProperties p = props("allow");
        p.getRobots().setAiAgentsExtra(List.of("retrieval=BrandNewSearchBot"));
        String out = new String(new RobotsTxtEndpoint(
                config(Map.of("robots.ai-training", "disallow", "robots.ai-retrieval", "allow")), p)
                .handle(req(SITE, Map.of())).body(), StandardCharsets.UTF_8);

        assertTrue(out.contains("User-agent: BrandNewSearchBot"), out);
        assertTrue(groupContaining(out, "BrandNewSearchBot").contains("Allow: /"),
                "declared retrieval, so it follows the retrieval policy: " + out);
        assertFalse(out.contains("retrieval=BrandNewSearchBot"), "the prefix must not leak into the file");
    }

    /**
     * A misspelt class still lists the agent, and the prefix is not an injection vector.
     *
     * <p>The alternative — folding the bad prefix into the name — yields a user-agent token that matches
     * nothing, so the operator believes they covered an agent they did not. That is the silent failure the
     * whole extra-agents list exists to prevent, so a typo is reported and the agent is honoured under the
     * site's overall policy.
     */
    @Test
    void aMisspeltClassStillListsTheAgentAndCannotInject() {
        DiscoverabilityProperties p = props("allow");
        p.getRobots().setAiAgentsExtra(List.of("nonsense=EvilBot", "x\nDisallow: /secret\nUser-agent: Y"));
        String out = new String(new RobotsTxtEndpoint(config(Map.of()), p)
                .handle(req(SITE, Map.of())).body(), StandardCharsets.UTF_8);

        assertTrue(out.contains("User-agent: EvilBot"), "the agent the operator named is listed: " + out);
        assertFalse(out.contains("nonsense"), "the bad prefix must not become part of the name: " + out);
        assertFalse(out.contains("Disallow: /secret"), "CR/LF injection stays closed: " + out);
    }

    /** Every built-in agent has a class, or the split silently sends it to the catch-all group. */
    @Test
    void everyBuiltInAgentIsClassified() {
        for (String agent : RobotsTxtEndpoint.AI_CRAWLERS) {
            assertTrue(RobotsTxtEndpoint.AI_CLASSES.containsKey(agent),
                    agent + " has no class — it would quietly fall under the overall policy instead");
        }
        assertEquals(RobotsTxtEndpoint.AI_CRAWLERS.size(), RobotsTxtEndpoint.AI_CLASSES.size(),
                "a class map entry naming an agent that is not in the list does nothing and will rot");
    }
}
