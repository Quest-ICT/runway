package mx.quest.runway.reload;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound {@code runway.reload.*} configuration for the site-reload endpoint ({@code POST /__reload/site/<id>}).
 *
 * <p><b>Off unless a token is set.</b> A blank {@link #token} makes the endpoint answer {@code 404} — the feature
 * is absent, not merely unauthenticated — so an install that never configures it exposes no new surface at all.
 */
@ConfigurationProperties("runway.reload")
public class ReloadProperties {

    /** Master switch. Even when {@code true} the endpoint stays inert until a token is set. */
    private boolean enabled = true;

    /**
     * Shared secret presented as {@code Authorization: Bearer <token>}. <b>Blank by default ⇒ endpoint disabled.</b>
     * A secret: it belongs in the external {@code application.yml} (already {@code 600}), which is writable by an
     * operator whose toolchain is file access only — that is the whole point of choosing a token over a CLI verb.
     */
    private String token = "";

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

    /** Whether the endpoint should answer at all — enabled AND holding a token. */
    public boolean active() {
        return enabled && !token.isEmpty();
    }
}
