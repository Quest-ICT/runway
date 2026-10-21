package mx.quest.protection;

import java.util.function.UnaryOperator;

/**
 * The on-disk YAML shape of a site's protection config ({@code sites/<id>/plugins/protection.yml}). It mirrors
 * {@link ProtectionSettings} but keeps the captcha secret in its <b>stored</b> form ({@code ENC(...)}); the
 * conversion to a usable {@link ProtectionSettings} decrypts it via the supplied function. A mutable class with a
 * no-arg constructor (not a record) so Jackson can bind a partial/hand-edited file field-by-field, defaulting any
 * omitted field — the store then normalizes through {@link ProtectionSettings}'s canonical constructor.
 */
public class StoredProtection {

    private boolean honeypotEnabled = true;
    private int maxAttempts = ProtectionSettings.DEFAULT_MAX_ATTEMPTS;
    private int windowSeconds = ProtectionSettings.DEFAULT_WINDOW_SECONDS;
    private int lockoutSeconds = ProtectionSettings.DEFAULT_LOCKOUT_SECONDS;
    private StoredCaptcha captcha = new StoredCaptcha();

    public StoredProtection() {
    }

    public StoredProtection(boolean honeypotEnabled, int maxAttempts, int windowSeconds, int lockoutSeconds,
                            StoredCaptcha captcha) {
        this.honeypotEnabled = honeypotEnabled;
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.lockoutSeconds = lockoutSeconds;
        this.captcha = captcha != null ? captcha : new StoredCaptcha();
    }

    /** Converts to the usable settings, decrypting the stored captcha secret with {@code decrypt}. */
    public ProtectionSettings toSettings(UnaryOperator<String> decrypt) {
        StoredCaptcha c = captcha != null ? captcha : new StoredCaptcha();
        ProtectionSettings.Captcha resolved = new ProtectionSettings.Captcha(
                c.isEnabled(), c.getProvider(), c.getSiteKey(), decrypt.apply(c.getSecretKey()),
                c.getScoreThreshold());
        return new ProtectionSettings(honeypotEnabled, maxAttempts, windowSeconds, lockoutSeconds, resolved);
    }

    public boolean isHoneypotEnabled() {
        return honeypotEnabled;
    }

    public void setHoneypotEnabled(boolean honeypotEnabled) {
        this.honeypotEnabled = honeypotEnabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int getLockoutSeconds() {
        return lockoutSeconds;
    }

    public void setLockoutSeconds(int lockoutSeconds) {
        this.lockoutSeconds = lockoutSeconds;
    }

    public StoredCaptcha getCaptcha() {
        return captcha;
    }

    public void setCaptcha(StoredCaptcha captcha) {
        this.captcha = captcha;
    }

    /** The on-disk captcha section; {@code secretKey} is the stored {@code ENC(...)} ciphertext. */
    public static class StoredCaptcha {
        private boolean enabled = false;
        private String provider = "recaptcha-v2";
        private String siteKey = "";
        private String secretKey = "";
        private double scoreThreshold = 0.5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getSiteKey() {
            return siteKey;
        }

        public void setSiteKey(String siteKey) {
            this.siteKey = siteKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public StoredCaptcha() {
        }

        public StoredCaptcha(boolean enabled, String provider, String siteKey, String secretKey,
                             double scoreThreshold) {
            this.enabled = enabled;
            this.provider = provider;
            this.siteKey = siteKey;
            this.secretKey = secretKey;
            this.scoreThreshold = scoreThreshold;
        }
    }
}
