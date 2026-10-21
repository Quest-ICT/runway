package mx.quest.runway.health;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound {@code runway.health.*} configuration for the per-site health probe ({@code /__health}).
 *
 * <p>The path itself is deliberately <b>not</b> configurable — see {@code SiteHealthPaths}: a configurable path
 * could not be reserved against tenant content.
 */
@ConfigurationProperties("runway.health")
public class HealthProperties {

    /** Whether the probe is served at all. Default on: it discloses nothing an anonymous visitor cannot infer. */
    private boolean enabled = true;

    /**
     * Shared secret that unlocks the DETAIL response (cause, site id, checks). Blank by default, which leaves the
     * detail shape entirely unavailable — the probe still answers, publicly, with status only. Presented as
     * {@code Authorization: Bearer <token>}; a wrong or absent token silently yields the public shape and is never
     * an error, so this can never become a credential oracle. It is a secret: it belongs in the external
     * {@code application.yml} (already {@code 600}).
     */
    private String token = "";

    /**
     * Whether to check that the site's templates are actually present ({@code _master.html} + a home page).
     * Default on: without it the probe reports green for a site whose every request 500s. Two {@code Files.exists}
     * calls, memoized for {@link #contentCheckTtlSeconds}.
     */
    private boolean contentCheck = true;

    /** How long a content-check result is reused before the files are stat'd again. Default 10s. */
    private int contentCheckTtlSeconds = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token.trim();
    }

    /** Whether a detail response is possible at all (a blank token disables the authenticated shape entirely). */
    public boolean detailAvailable() {
        return !token.isEmpty();
    }

    public boolean isContentCheck() {
        return contentCheck;
    }

    public void setContentCheck(boolean contentCheck) {
        this.contentCheck = contentCheck;
    }

    public int getContentCheckTtlSeconds() {
        return contentCheckTtlSeconds;
    }

    public void setContentCheckTtlSeconds(int contentCheckTtlSeconds) {
        this.contentCheckTtlSeconds = contentCheckTtlSeconds > 0 ? contentCheckTtlSeconds : 10;
    }
}
