package mx.quest.runway.log;

/**
 * A site's log preferences ({@code sites/<id>/log.yml}): whether client IPs are anonymized before they are
 * written, and how many days of logs to keep. Both fields are nullable so an absent file means "defaults";
 * the effective values are read through the helper methods (retention clamped to the app-level cap).
 *
 * @param anonymizeIp  {@code true}/{@code false}, or {@code null} for the default (anonymize on)
 * @param retentionDays days to keep, or {@code null} for the default (30); always clamped to the platform cap
 */
public record SiteLogConfig(Boolean anonymizeIp, Integer retentionDays) {

    /** Absent-file defaults: anonymize on, 30-day retention. */
    public static final SiteLogConfig DEFAULT = new SiteLogConfig(null, null);

    private static final int DEFAULT_RETENTION = 30;

    /** Whether client IPs are anonymized at write time (default {@code true} when unset). */
    public boolean anonymize() {
        return anonymizeIp == null || anonymizeIp;
    }

    /** The effective retention in days, defaulted and clamped to {@code [1, cap]}. */
    public int effectiveRetentionDays(int cap) {
        int days = (retentionDays == null) ? DEFAULT_RETENTION : retentionDays;
        return Math.max(1, Math.min(days, Math.max(1, cap)));
    }
}
