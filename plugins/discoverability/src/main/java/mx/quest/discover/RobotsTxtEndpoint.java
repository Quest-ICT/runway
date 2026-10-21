package mx.quest.discover;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * SEO+/GEO — serves a per-site <b>{@code /robots.txt}</b>. The kernel deliberately does <b>not</b> own this
 * route (an {@code EndpointContributor} route has exactly one owner, and a kernel handler reading a plugin's
 * config would hard-code a plugin id into the core); the discoverability plugin owns it because the two things
 * a rich {@code robots.txt} needs are already the plugin's: the {@code Sitemap:} line points at the plugin's own
 * {@code /sitemap.xml} (and is emitted only when that surface is enabled, so it never points at a {@code 404}),
 * and the AI/LLM-crawler policy is a GEO concern that sits beside {@code llms.txt}.
 *
 * <p>The body is an <b>explicit</b> named AI/LLM-crawler group (always emitted, so the AI posture is stated
 * rather than merely implied by the wildcard), then a general {@code User-agent: *} group (allow-all, plus any
 * tenant {@code robots.extra-disallow} paths), followed by the {@code Sitemap:} line. On the allow posture the
 * AI group mirrors the general group's exclusions (a most-specific match wins in robots.txt, so a bare
 * {@code Allow: /} would let those agents bypass the tenant's own Disallow paths); on opt-out it is a clean
 * {@code Disallow: /}. The AI posture is per-tenant ({@code robots.ai-policy}: {@code allow}/{@code disallow}/
 * {@code inherit}) falling back to the app-level default ({@code robots.ai-default}, GEO-friendly {@code allow}).
 * {@code robots.custom} is a full-body escape hatch that replaces the generated output verbatim.
 *
 * <p>An install <b>without</b> this plugin serves no {@code /robots.txt} at all, which crawlers treat as
 * allow-all — functionally identical to the old permissive kernel default.
 */
public class RobotsTxtEndpoint implements EndpointContributor {

    /**
     * AI/LLM crawler user-agents grouped under one {@code Disallow: /} when a site opts out of AI crawling.
     * These are training/answer-engine agents kept distinct from the classic search crawlers (e.g.
     * {@code Google-Extended} ≠ {@code Googlebot}, {@code Applebot-Extended} ≠ {@code Applebot}), so opting out
     * of AI never deindexes the site from web search.
     *
     * <p><b>This list goes stale, and it fails silently when it does.</b> Operators publish, rename and retire
     * agent names on their own schedule; an agent missing from this list is simply <i>not covered</i> by an
     * opt-out, and nothing anywhere reports that — the site owner believes they opted out and did not. That is
     * not hypothetical: {@code Claude-User} and {@code Claude-SearchBot} were absent from the shipped 1.3.0 list
     * while {@code ClaudeBot} was present, so a site set to {@code disallow} was still reachable by two of the
     * three Anthropic agents.
     *
     * <p>Because a compile-time constant cannot be corrected without a plugin release, the effective list is
     * this constant <b>plus</b> the app-level {@code robots.ai-agents-extra} setting
     * ({@link DiscoverabilityProperties.Robots#getAiAgentsExtra()}) — so an operator can cover a newly announced
     * agent the same day it is announced. See {@link #aiAgents()}.
     *
     * <p>Each agent is also assigned an {@link AiClass}, so a site can decline <b>training</b> while staying
     * citable by answer engines — see {@link #AI_CLASSES}.
     */
    static final List<String> AI_CRAWLERS = List.of(
            "GPTBot", "OAI-SearchBot", "ChatGPT-User",   // OpenAI: training / retrieval / user-triggered
            "ClaudeBot", "Claude-SearchBot", "Claude-User",   // Anthropic: training / retrieval / user-triggered
            "anthropic-ai", "Claude-Web",                // Anthropic legacy names, kept: harmless if retired
            "Google-Extended",                           // Google (Gemini training; NOT Googlebot)
            "PerplexityBot", "Perplexity-User",          // Perplexity: retrieval / user-triggered
            "CCBot",                                     // Common Crawl (feeds many LLMs)
            "Bytespider",                                // ByteDance
            "Amazonbot",                                 // Amazon
            "Applebot-Extended",                         // Apple (AI training opt-out; NOT Applebot)
            "Meta-ExternalAgent", "FacebookBot",         // Meta
            "cohere-ai", "Diffbot", "Omgilibot", "Timpibot", "YouBot");

    /**
     * What an AI agent is <i>for</i>. The three roles the operators themselves distinguish, and the reason a
     * single AI switch was not enough: they carry opposite consequences for a site.
     */
    enum AiClass {

        /** Collecting text to train a model. Declining costs the site nothing it can see. */
        TRAINING("training"),

        /** Building an index an answer engine cites from. Declining removes the site from those answers. */
        RETRIEVAL("retrieval"),

        /** Fetching one page because a person asked about it. Declining breaks a visit somebody initiated. */
        USER("user");

        private final String key;

        AiClass(String key) {
            this.key = key;
        }

        /** The token used in setting keys and in operator-supplied {@code class=Agent} entries. */
        String key() {
            return key;
        }

        static AiClass of(String token) {
            for (AiClass value : values()) {
                if (value.key.equalsIgnoreCase(token)) {
                    return value;
                }
            }
            return null;
        }
    }

    /**
     * Which class each built-in agent belongs to.
     *
     * <h2>How these were assigned, and where the uncertainty is</h2>
     * The triads are the operators' own published distinction and are not in doubt: OpenAI's
     * {@code GPTBot}/{@code OAI-SearchBot}/{@code ChatGPT-User}, Anthropic's
     * {@code ClaudeBot}/{@code Claude-SearchBot}/{@code Claude-User}, Perplexity's
     * {@code PerplexityBot}/{@code Perplexity-User}. So are the training-only opt-out agents that exist
     * precisely to be separable from their search siblings — {@code Google-Extended} (not {@code Googlebot}),
     * {@code Applebot-Extended} (not {@code Applebot}).
     *
     * <p>The rest are a best-effort reading of each operator's own description, and some genuinely serve more
     * than one purpose. <b>An agent whose role is ambiguous is placed in {@link AiClass#TRAINING}</b>, because
     * that is the class a site opting out is most likely to mean, and a misplaced agent should fail toward
     * honouring the site's stated intent rather than around it.
     *
     * <p>An agent absent from this map is governed by the site's overall {@code robots.ai-policy} — the
     * behaviour every site had before classes existed, so an operator-added name keeps working unchanged.
     */
    static final Map<String, AiClass> AI_CLASSES = Map.ofEntries(
            Map.entry("GPTBot", AiClass.TRAINING),
            Map.entry("OAI-SearchBot", AiClass.RETRIEVAL),
            Map.entry("ChatGPT-User", AiClass.USER),
            Map.entry("ClaudeBot", AiClass.TRAINING),
            Map.entry("Claude-SearchBot", AiClass.RETRIEVAL),
            Map.entry("Claude-User", AiClass.USER),
            Map.entry("anthropic-ai", AiClass.TRAINING),
            Map.entry("Claude-Web", AiClass.TRAINING),
            Map.entry("Google-Extended", AiClass.TRAINING),
            Map.entry("PerplexityBot", AiClass.RETRIEVAL),
            Map.entry("Perplexity-User", AiClass.USER),
            Map.entry("CCBot", AiClass.TRAINING),
            Map.entry("Bytespider", AiClass.TRAINING),
            Map.entry("Amazonbot", AiClass.TRAINING),
            Map.entry("Applebot-Extended", AiClass.TRAINING),
            Map.entry("Meta-ExternalAgent", AiClass.TRAINING),
            Map.entry("FacebookBot", AiClass.TRAINING),
            Map.entry("cohere-ai", AiClass.TRAINING),
            Map.entry("Diffbot", AiClass.TRAINING),
            Map.entry("Omgilibot", AiClass.TRAINING),
            Map.entry("Timpibot", AiClass.TRAINING),
            Map.entry("YouBot", AiClass.RETRIEVAL));

    /**
     * One AI agent as it will be emitted: its name, and the class governing it ({@code null} = the site's
     * overall policy).
     *
     * <p>Name <b>and</b> class together, rather than a name list beside a shared lookup map, because
     * {@code /robots.txt} is a public route served on many threads at once: a map rebuilt per request would
     * let one thread read it mid-rebuild and emit an agent under a policy the operator never chose. There is
     * no shared mutable state here at all — the classification is derived per call and thrown away.
     */
    record Agent(String name, AiClass declaredClass) {
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RobotsTxtEndpoint.class);

    private final PluginSettings settings;
    private final DiscoverabilityProperties properties;

    public RobotsTxtEndpoint(PluginSettings settings, DiscoverabilityProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    @Override
    public String key() {
        return "discoverability/robots";
    }

    @Override
    public List<Route> routes() {
        // Not indexable, not in sitemap, cacheable (content is per-host stable).
        return List.of(new Route("GET", "/robots.txt", Realm.TENANT, new SurfacePosture(false, false, true)));
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        Map<String, Object> cfg = settings.site(req.site().id()).values();

        // Full-body override — replaces the generated output entirely.
        String custom = SiteConfig.str(cfg, "robots.custom", "");
        if (!custom.isBlank()) {
            return RouteResponse.text(200, ensureTrailingNewline(custom));
        }

        String base = PageUrls.base(req.site(), req);
        StringBuilder body = new StringBuilder(256);
        List<String> extra = extraDisallow(cfg);

        // Named AI/LLM-crawler group(s), ALWAYS emitted so the crawl posture is EXPLICIT rather than implied by
        // the wildcard (a GEO signal). On opt-out a group is a clean Disallow: /. On allow it MIRRORS the
        // general group (Allow: / + the same tenant Disallow paths) — a most-specific match wins in robots.txt,
        // so an explicit Allow: / with no exclusions would otherwise let these agents ignore the site's own
        // extra Disallows.
        //
        // ONE group when every class resolves the same way — which is every site that has not set a per-class
        // override, so their robots.txt is byte-identical to before classes existed. Splitting a site that made
        // no such choice into three identical groups would churn every crawler's copy to say the same thing.
        appendAiGroups(body, cfg, extra);

        // The general group: allow-all, plus any tenant-declared extra Disallow paths.
        body.append("User-agent: *\n");
        appendAllowWithExclusions(body, extra);

        // Sitemap reference — only when the sitemap surface is actually served (matches SitemapEndpoint's gate).
        if (!base.isBlank() && SiteConfig.bool(cfg, "sitemap.enabled", true)) {
            body.append("\nSitemap: ").append(base).append("/sitemap.xml\n");
        }

        return RouteResponse.text(200, body.toString());
    }


    /**
     * Writes the AI-crawler group(s): one per distinct policy across the three classes.
     *
     * <p>A site that has expressed no per-class preference gets exactly the single group it always got —
     * identical bytes, identical agent order. Only a site that actually distinguishes the classes pays the
     * extra groups.
     */
    private void appendAiGroups(StringBuilder body, Map<String, Object> cfg, List<String> extra) {
        boolean training = aiDisallowed(cfg, AiClass.TRAINING);
        boolean retrieval = aiDisallowed(cfg, AiClass.RETRIEVAL);
        boolean user = aiDisallowed(cfg, AiClass.USER);
        List<Agent> agents = aiAgentsClassified();

        if (training == retrieval && retrieval == user) {
            appendAiGroup(body, agents.stream().map(Agent::name).toList(), training, extra);
            return;
        }
        // Split. Unclassified agents (operator additions with no class= prefix) follow the site's overall
        // policy, exactly as they did before classes existed — inventing a class for a name we do not
        // recognise would silently apply a policy the operator never chose.
        for (AiClass aiClass : AiClass.values()) {
            List<String> ofClass = agents.stream()
                    .filter(a -> classOf(a) == aiClass).map(Agent::name).toList();
            if (!ofClass.isEmpty()) {
                appendAiGroup(body, ofClass, aiDisallowed(cfg, aiClass), extra);
            }
        }
        List<String> unclassified = agents.stream()
                .filter(a -> classOf(a) == null).map(Agent::name).toList();
        if (!unclassified.isEmpty()) {
            appendAiGroup(body, unclassified, aiDisallowed(cfg), extra);
        }
    }

    /** One {@code User-agent:} block: the names, then either {@code Disallow: /} or the mirrored allow. */
    private static void appendAiGroup(StringBuilder body, List<String> agents, boolean disallowed,
                                      List<String> extra) {
        for (String agent : agents) {
            body.append("User-agent: ").append(agent).append('\n');
        }
        if (disallowed) {
            body.append("Disallow: /\n\n");
        } else {
            appendAllowWithExclusions(body, extra);
            body.append('\n');
        }
    }

    /** The class of {@code agent} — its declared {@code class=} prefix if any, else the built-in map. */
    private static AiClass classOf(Agent agent) {
        return agent.declaredClass() != null ? agent.declaredClass() : AI_CLASSES.get(agent.name());
    }

    /**
     * Whether {@code aiClass} is disallowed for this site: its own override if set, else the site's overall
     * {@code robots.ai-policy}, else the app-level default.
     *
     * <p>The per-class key is {@code robots.ai-training} / {@code -retrieval} / {@code -user} — deliberately
     * NOT {@code robots.ai-policy.training}, which would make one key both a scalar and a map prefix and is
     * the kind of shape that parses today and breaks the first time the config is read as a tree.
     */
    private boolean aiDisallowed(Map<String, Object> cfg, AiClass aiClass) {
        String policy = SiteConfig.str(cfg, "robots.ai-" + aiClass.key(), "inherit").trim();
        if (policy.equalsIgnoreCase("allow")) {
            return false;
        }
        if (policy.equalsIgnoreCase("disallow")) {
            return true;
        }
        return aiDisallowed(cfg);                       // inherit → the site's overall AI policy
    }

    /**
     * The built-in {@link #AI_CRAWLERS} plus any operator-added names from {@code robots.ai-agents-extra},
     * de-duplicated case-insensitively and sanitised.
     *
     * <p>Sanitisation is not cosmetic: an operator-supplied value lands in a {@code User-agent:} line, so a name
     * containing CR/LF would let one setting inject arbitrary robots directives into every tenant's file — the
     * same injection this class already closes for tenant {@code extra-disallow} paths. Whitespace and colons are
     * stripped for the same reason (a colon would forge a second directive on one line).
     *
     * <p>Order is stable: built-ins first in declaration order, then operator additions in the order given, so
     * the emitted file diffs cleanly between restarts.
     */
    List<String> aiAgents() {
        return aiAgentsClassified().stream().map(Agent::name).toList();
    }

    /** As {@link #aiAgents()}, but each name paired with the class its entry declared (if any). */
    List<Agent> aiAgentsClassified() {
        List<Agent> agents = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String a : AI_CRAWLERS) {
            agents.add(new Agent(a, null));
            seen.add(a.toLowerCase(Locale.ROOT));
        }
        for (String raw : properties.getRobots().getAiAgentsExtra()) {
            if (raw == null) {
                continue;
            }
            // An entry may declare which class the agent belongs to: `training=SomeNewBot`. Without it the
            // agent follows the site's overall AI policy, which is what every operator addition did before
            // classes existed — so nothing already configured changes meaning.
            String value = raw;
            AiClass declared = null;
            int eq = value.indexOf('=');
            if (eq > 0) {
                String token = value.substring(0, eq).trim();
                declared = AiClass.of(token);
                // A misspelt class is reported and the AGENT is still honoured, unclassified. Folding the bad
                // prefix into the name instead would produce a user-agent token that matches nothing, and the
                // operator would believe they had covered an agent they had not — the exact silent failure
                // this whole list exists to warn about.
                if (declared == null) {
                    log.warn("robots.ai-agents-extra entry '{}' names an unknown AI class '{}' — the agent is "
                            + "still listed, but it follows the site's overall AI policy. Valid classes: {}.",
                            raw, token, java.util.Arrays.stream(AiClass.values()).map(AiClass::key).toList());
                }
                value = value.substring(eq + 1);
            }
            String clean = value.replaceAll("[\\r\\n:=\\s]", "").trim();
            if (!clean.isEmpty() && seen.add(clean.toLowerCase(Locale.ROOT))) {
                agents.add(new Agent(clean, declared));
            }
        }
        return List.copyOf(agents);
    }

    /** True when this site opts out of AI/LLM crawlers: per-tenant {@code robots.ai-policy}, else the app default. */
    private boolean aiDisallowed(Map<String, Object> cfg) {
        String policy = SiteConfig.str(cfg, "robots.ai-policy", "inherit").trim();
        if (policy.equalsIgnoreCase("allow")) {
            return false;
        }
        if (policy.equalsIgnoreCase("disallow")) {
            return true;
        }
        return "disallow".equalsIgnoreCase(properties.getRobots().getAiDefault()); // inherit → app-level default
    }

    /** Writes {@code Allow: /} followed by one {@code Disallow:} line per tenant exclusion path. */
    private static void appendAllowWithExclusions(StringBuilder body, List<String> extra) {
        body.append("Allow: /\n");
        for (String path : extra) {
            body.append("Disallow: ").append(path).append('\n');
        }
    }

    /** Tenant extra Disallow paths, each normalized to start with {@code /} and stripped of CR/LF. */
    private static List<String> extraDisallow(Map<String, Object> cfg) {
        List<String> paths = new ArrayList<>();
        for (String p : SiteConfig.list(cfg, "robots.extra-disallow")) {
            // Strip CR/LF so one list entry can never inject extra robots lines/directives.
            String clean = p.replaceAll("[\\r\\n]", "").trim();
            if (!clean.isEmpty()) {
                paths.add(clean.startsWith("/") ? clean : "/" + clean);
            }
        }
        return paths;
    }

    private static String ensureTrailingNewline(String s) {
        return s.endsWith("\n") ? s : s + "\n";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("endpoint.tenant", "endpoint.robots");
    }
}
