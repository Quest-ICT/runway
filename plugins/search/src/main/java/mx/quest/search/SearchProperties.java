package mx.quest.search;

/**
 * The plugin's <b>app-level</b> settings — the platform concerns, bound from the UI-owned property source
 * {@code external/plugin-settings/search.yml} under {@code runway.plugin.search.*} (declared as {@code settings:}
 * in {@code plugin.yml}; applied on restart). Bound by the {@code @ConfigurationProperties} on its {@code @Bean}
 * method ({@code searchProperties} in {@link SearchConfig}) rather than {@code @EnableConfigurationProperties}, so the
 * bean carries the plugin-id prefix every plugin bean name needs. These are deliberately NOT per-tenant: a tenant must not be able to
 * set filesystem/timer/abuse knobs that affect the platform.
 *
 * <ul>
 *   <li>{@code refresh-minutes} — how long a built-in per-site index stays fresh before it is rebuilt on the next
 *       query. It re-captures changes an event can't announce (notably i18n edits) without a manual reindex.</li>
 *   <li>{@code rate-limit.*} — the per-IP query ceiling on the public search endpoints, a small abuse guard so a
 *       query flood can't hammer the index. Two windows: per minute and per hour.</li>
 * </ul>
 */
public class SearchProperties {

    /** Minutes a built-in per-site index is considered fresh before the next query rebuilds it (default 60). */
    private int refreshMinutes = 60;

    private final RateLimit rateLimit = new RateLimit();

    public int getRefreshMinutes() {
        return refreshMinutes;
    }

    public void setRefreshMinutes(int refreshMinutes) {
        this.refreshMinutes = refreshMinutes;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /** Freshness TTL in millis, floored at one minute so a misconfigured 0 can't rebuild on every query. */
    long freshnessMillis() {
        return Math.max(1, refreshMinutes) * 60_000L;
    }

    /** The two-window per-IP ceiling for the public search endpoints. */
    public static class RateLimit {

        /** Maximum queries one IP may make per rolling minute (default 30). */
        private int perIpPerMinute = 30;

        /** Maximum queries one IP may make per rolling hour (default 600). */
        private int perIpPerHour = 600;

        public int getPerIpPerMinute() {
            return perIpPerMinute;
        }

        public void setPerIpPerMinute(int perIpPerMinute) {
            this.perIpPerMinute = perIpPerMinute;
        }

        public int getPerIpPerHour() {
            return perIpPerHour;
        }

        public void setPerIpPerHour(int perIpPerHour) {
            this.perIpPerHour = perIpPerHour;
        }
    }
}
