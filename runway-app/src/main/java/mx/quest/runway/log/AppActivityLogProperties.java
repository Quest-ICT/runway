package mx.quest.runway.log;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level settings for the <b>application-realm activity log</b> ({@code runway.app-activity.*}). This is
 * the operator-action audit trail shown on the app console's Activity → Logs screen; it is deliberately app-level
 * configuration, not tenant-editable. Distinct from {@link LogProperties} (the per-site log capture), which
 * governs the tenant realm.
 */
@ConfigurationProperties("runway.app-activity")
public class AppActivityLogProperties {

    /** Whether application-realm activity is recorded at all. Default: on. */
    private boolean enabled = true;

    /** How many days of activity day-files are kept; older files are pruned. Default: 90. */
    private int retentionDays = 90;

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
}
