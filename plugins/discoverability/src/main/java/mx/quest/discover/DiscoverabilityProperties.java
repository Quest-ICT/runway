package mx.quest.discover;

import java.util.ArrayList;
import java.util.List;

/**
 * App-level settings for the discoverability plugin, bound from {@code runway.plugin.discoverability.*}
 * (the host loads {@code external/plugin-settings/discoverability.yml} as a property source). These are
 * <b>platform</b> concerns — the default AI/LLM-crawler posture a site inherits, the IndexNow egress switch — never
 * per-tenant behaviour (which lives in the descriptor's {@code siteSettings} and is read live via
 * {@code PluginSettings}). Bound by {@code @ConfigurationProperties} on its {@code @Bean} method in
 * {@link DiscoverabilityConfig#discoverabilityProperties()}; applied on restart.
 */
public class DiscoverabilityProperties {

    private Robots robots = new Robots();
    private IndexNow indexnow = new IndexNow();

    public Robots getRobots() {
        return robots;
    }

    public void setRobots(Robots robots) {
        this.robots = robots;
    }

    public IndexNow getIndexnow() {
        return indexnow;
    }

    public void setIndexnow(IndexNow indexnow) {
        this.indexnow = indexnow == null ? new IndexNow() : indexnow;
    }

    /**
     * IndexNow platform settings — the operator's control over whether this deployment talks to third parties
     * at all.
     *
     * <h2>Why the platform switch exists and why it is OFF</h2>
     * IndexNow is the first thing in this plugin that makes an <b>outbound</b> request on a tenant's behalf: a
     * tenant enabling it causes this server to POST that tenant's URLs to an external service. Who this
     * deployment is allowed to talk to is the <b>platform operator's</b> decision — they own the network, the
     * egress firewall and the incident if it misbehaves — so a tenant cannot switch it on unilaterally.
     *
     * <p>Off by default because an upgrade must never start making network calls a deployment was not making
     * before. A tenant who enables it while the platform has not gets a WARN naming the tenant and the setting
     * to flip, rather than silence.
     */
    public static class IndexNow {

        /** Whether tenants of this deployment may use IndexNow at all. Off until the operator permits it. */
        private boolean enabled = false;

        /**
         * The submission endpoint. Configurable so an operator can point at a specific engine's endpoint or an
         * internal proxy that carries their egress policy; the default is the shared endpoint, which forwards
         * to every participating engine.
         */
        private String endpoint = "https://api.indexnow.org/indexnow";

        /**
         * Seconds to gather changes before submitting. Batching is not an optimisation here — a page save
         * touching five URLs must not become five outbound requests, and an editor working through a section
         * would otherwise generate one per keystroke-to-save.
         */
        private int batchSeconds = 60;

        /**
         * Most URLs in one submission. The spec allows 10,000; a lower cap bounds both the request body and how
         * much a single misbehaving tenant can send. Overflow is dropped with a WARN, never silently.
         */
        private int maxUrlsPerSubmission = 1000;

        /** Most URLs queued per site between submissions. A bound on memory during a bulk edit or a restore. */
        private int maxQueuedPerSite = 5000;

        /** Connect/read timeout for the submission. Short: nothing waits on it, and a hung call helps nobody. */
        private int timeoutSeconds = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public int getBatchSeconds() {
            return batchSeconds;
        }

        public void setBatchSeconds(int batchSeconds) {
            this.batchSeconds = batchSeconds;
        }

        public int getMaxUrlsPerSubmission() {
            return maxUrlsPerSubmission;
        }

        public void setMaxUrlsPerSubmission(int maxUrlsPerSubmission) {
            this.maxUrlsPerSubmission = maxUrlsPerSubmission;
        }

        public int getMaxQueuedPerSite() {
            return maxQueuedPerSite;
        }

        public void setMaxQueuedPerSite(int maxQueuedPerSite) {
            this.maxQueuedPerSite = maxQueuedPerSite;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /** {@code /robots.txt} platform defaults. */
    public static class Robots {

        /**
         * The default AI/LLM-crawler policy a site inherits when its per-tenant {@code robots.ai-policy} is
         * {@code inherit} (the default): {@code allow} (GEO-friendly — the platform default) or {@code disallow}
         * (opt every un-overriding site out of AI crawling). Bound from {@code robots.ai-default}.
         */
        private String aiDefault = "allow";

        public String getAiDefault() {
            return aiDefault;
        }

        public void setAiDefault(String aiDefault) {
            this.aiDefault = aiDefault;
        }

        /**
         * Extra AI/LLM crawler user-agent names to cover, <b>appended</b> to the plugin's built-in list. Bound
         * from {@code robots.ai-agents-extra}; empty by default, so the shipped behaviour is unchanged.
         *
         * <p>This exists because the built-in list is a compile-time constant that goes stale silently: when an
         * operator announces a new agent, every site set to {@code disallow} is un-covered for it until a plugin
         * release ships — and nothing reports the gap. This setting closes it the same day, without a release.
         *
         * <p>It is <b>app-level, not per-tenant</b>, deliberately: the set of agents that exist in the world is a
         * platform fact, not tenant-owned behaviour. What a tenant chooses to <i>do</i> about them stays
         * per-tenant, in {@code robots.ai-policy}.
         *
         * <p>Additive only, by design — there is no removal list. Naming an agent that has been retired costs one
         * inert {@code User-agent:} line; being unable to add one that exists costs an un-covered opt-out.
         */
        private List<String> aiAgentsExtra = new ArrayList<>();

        public List<String> getAiAgentsExtra() {
            return aiAgentsExtra;
        }

        public void setAiAgentsExtra(List<String> aiAgentsExtra) {
            this.aiAgentsExtra = aiAgentsExtra == null ? new ArrayList<>() : aiAgentsExtra;
        }
    }
}
