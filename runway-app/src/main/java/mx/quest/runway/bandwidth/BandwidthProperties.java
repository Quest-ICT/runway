package mx.quest.runway.bandwidth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Platform-level bandwidth-metering settings ({@code runway.bandwidth.*}) — app-level, not tenant-editable
 * (a tenant-settable retention/path/quota would be an abuse lever). Metering is aggregate byte counts only.
 */
@ConfigurationProperties("runway.bandwidth")
public class BandwidthProperties {

    /** Whether per-site bandwidth metering runs at all. Default: on. */
    private boolean enabled = true;

    /** How many days of hourly buckets to keep (older buckets are pruned from memory and the CSV). */
    private int retentionDays = 90;

    /** How often the in-memory counters are snapshotted to disk. */
    private int snapshotSeconds = 60;

    @NestedConfigurationProperty
    private final Quota quota = new Quota();

    public Quota getQuota() {
        return quota;
    }

    /**
     * Optional per-site rolling-30-day <em>outbound</em> bandwidth quota. When a site's last-30-day served
     * bytes exceed its effective quota, its <b>public</b> traffic is short-circuited with {@code 503} (the
     * console and login stay reachable). App-level and operator-set on purpose — a tenant raising its own cap
     * would defeat the point. Off by default (no cap). The check is O(1) at request time (a cached set,
     * recomputed on the metering snapshot cadence), so it never burdens the hot path.
     */
    public static class Quota {

        /** Default per-site monthly (rolling-30-day) outbound byte cap; {@code 0} = unlimited. */
        private long defaultMonthlyBytes = 0;

        /** Per-site overrides of {@link #defaultMonthlyBytes}, keyed by site id ({@code 0} = unlimited). */
        private Map<String, Long> perSite = new LinkedHashMap<>();

        public long getDefaultMonthlyBytes() {
            return defaultMonthlyBytes;
        }

        public void setDefaultMonthlyBytes(long defaultMonthlyBytes) {
            this.defaultMonthlyBytes = defaultMonthlyBytes;
        }

        public Map<String, Long> getPerSite() {
            return perSite;
        }

        public void setPerSite(Map<String, Long> perSite) {
            this.perSite = (perSite == null) ? new LinkedHashMap<>() : perSite;
        }

        /** The effective monthly cap for {@code siteId} — the per-site override if set, else the default. */
        public long effectiveFor(String siteId) {
            return perSite.getOrDefault(siteId, defaultMonthlyBytes);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getSnapshotSeconds() {
        return snapshotSeconds;
    }

    public void setSnapshotSeconds(int snapshotSeconds) {
        this.snapshotSeconds = snapshotSeconds;
    }
}
