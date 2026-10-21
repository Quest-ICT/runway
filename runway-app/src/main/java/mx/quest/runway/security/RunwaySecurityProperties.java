package mx.quest.runway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound {@code runway.security.*} configuration. The {@link #appMasterId} pin is written by the
 * first-run wizard at provisioning and is the structural identity of the undeletable application
 * superadmin (matched by stable principal id, never by username); it is blank before provisioning
 * (the WIZARD state). The password settings drive the one shared Argon2id encoder.
 */
@ConfigurationProperties("runway.security")
public class RunwaySecurityProperties {

    /** The pinned application-superadmin principal id (blank until the wizard provisions it). */
    private String appMasterId;

    /** The Argon2id memory-cost profile (default STANDARD = 19 MiB). */
    private PasswordStrength passwordStrength = PasswordStrength.STANDARD;

    /** The most password hashes computed at once before requests fail "busy". */
    private int maxConcurrentHashes = 8;

    /** How long a request waits for a hashing slot before failing "busy", in milliseconds. */
    private long acquireTimeoutMillis = 2000;

    /**
     * The installation's master secret key for symmetric (AES-256-GCM) encryption of secret-typed plugin
     * settings, written to disk in the tagged {@code ENC(...)} form. Blank until configured — while blank the
     * secret cipher is unavailable, so a plugin's {@code secret} settings cannot be saved (the form says so).
     * Holds a real secret, so it belongs in the external {@code application.yml} (chmod 600), never the
     * classpath default; a future wizard step will generate it at provisioning, as it does the master pin.
     *
     * <p><b>Entropy requirement.</b> The AES-256 key is derived from this string by a single <b>SHA-256</b>
     * (no salt, no stretching), so this value must be a <b>long, high-entropy random string</b> — generate it,
     * e.g. {@code openssl rand -base64 48} — <b>not a human passphrase</b> (an unstretched low-entropy secret is
     * brute-forceable). Losing or changing it makes existing {@code ENC(...)} values undecryptable; back it up
     * with the deployment's secrets.
     */
    private String secretKey;

    public String getAppMasterId() {
        return appMasterId;
    }

    public void setAppMasterId(String appMasterId) {
        this.appMasterId = appMasterId;
    }

    public PasswordStrength getPasswordStrength() {
        return passwordStrength;
    }

    public void setPasswordStrength(PasswordStrength passwordStrength) {
        this.passwordStrength = passwordStrength;
    }

    public int getMaxConcurrentHashes() {
        return maxConcurrentHashes;
    }

    public void setMaxConcurrentHashes(int maxConcurrentHashes) {
        this.maxConcurrentHashes = maxConcurrentHashes;
    }

    public long getAcquireTimeoutMillis() {
        return acquireTimeoutMillis;
    }

    public void setAcquireTimeoutMillis(long acquireTimeoutMillis) {
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
