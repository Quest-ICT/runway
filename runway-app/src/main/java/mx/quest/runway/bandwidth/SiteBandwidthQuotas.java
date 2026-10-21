package mx.quest.runway.bandwidth;

import java.util.Optional;
import mx.quest.runway.platform.HostSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.springframework.stereotype.Component;

/**
 * Each site's monthly (rolling-30-day) outbound bandwidth cap, and where it comes from. The operator sets it per
 * site on the Sites screen, and the site sees
 * it read-only.
 *
 * <h2>Where a cap comes from, first match wins</h2>
 * <ol>
 *   <li><b>The server</b> — {@code runway.bandwidth.quota.per-site.<id>} in the host's {@code application.yml} (or
 *       an environment variable). One home per setting: the host's value wins and the Sites form shows the
 *       field locked. Bound at startup.</li>
 *   <li><b>The Sites screen</b> — {@code siteQuotas} in {@code external/platform.yml}. Read live, so a change takes
 *       effect at the next over-quota check (the metering snapshot, once a minute by default) with no restart.</li>
 *   <li><b>The platform default</b> — {@code runway.bandwidth.quota.default-monthly-bytes} (the Bandwidth settings
 *       card or the host file). Bound at startup.</li>
 * </ol>
 * {@code 0} means unlimited at every level.
 */
@Component
public class SiteBandwidthQuotas {

    /** Where a site's cap comes from. */
    public enum Source {
        /** The host's configuration sets this site's cap. */
        HOST,
        /** The operator set this site's cap on the Sites screen. */
        SITE,
        /** No cap of its own: the platform default applies. */
        DEFAULT
    }

    /**
     * A site's cap.
     *
     * @param bytes  the cap in bytes; {@code 0} = unlimited
     * @param source where it comes from
     * @param origin for {@link Source#HOST}, where the host sets it ("external/application.yml", …); else {@code null}
     */
    public record Quota(long bytes, Source source, String origin) {

        /** Whether the site is capped at all. */
        public boolean limited() {
            return bytes > 0;
        }

        /** The cap for people ("10 GiB"); "unlimited" is left to the caller's language. */
        public String human() {
            return Bytes.human(bytes);
        }
    }

    private final BandwidthProperties props;
    private final PlatformSettingsStore platform;
    private final HostSettings host;

    public SiteBandwidthQuotas(BandwidthProperties props, PlatformSettingsStore platform, HostSettings host) {
        this.props = props;
        this.platform = platform;
        this.host = host;
    }

    /** {@code siteId}'s cap and its source. */
    public Quota of(String siteId) {
        Long fromHost = props.getQuota().getPerSite().get(siteId);
        if (fromHost != null) {
            String origin = host.origin(PREFIX + siteId).orElse("the server's configuration");
            return new Quota(Math.max(0, fromHost), Source.HOST, origin);
        }
        Long own = platform.get().siteQuotas().get(siteId);
        if (own != null) {
            return new Quota(Math.max(0, own), Source.SITE, null);
        }
        return new Quota(Math.max(0, props.getQuota().getDefaultMonthlyBytes()), Source.DEFAULT, null);
    }

    /** {@code siteId}'s cap in bytes, {@code 0} = unlimited. */
    public long bytesFor(String siteId) {
        return of(siteId).bytes();
    }

    /** The platform default cap in bytes ({@code 0} = unlimited). */
    public long defaultBytes() {
        return Math.max(0, props.getQuota().getDefaultMonthlyBytes());
    }

    /** Where the host sets {@code siteId}'s cap, when it does: the Sites form then shows the field locked. */
    public Optional<String> hostLock(String siteId) {
        Quota quota = of(siteId);
        return quota.source() == Source.HOST ? Optional.of(quota.origin()) : Optional.empty();
    }

    private static final String PREFIX = "runway.bandwidth.quota.per-site.";
}
