package mx.quest.protection;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The plugin's app-level settings, bound from {@code runway.plugin.protection.*} (the kernel loads
 * {@code external/plugin-settings/protection.yml} as a property source). These are <b>platform</b>
 * concerns (abuse thresholds, IP lists) — app-level, never per-tenant.
 */
@ConfigurationProperties("runway.plugin.protection")
public class ProtectionProperties {

    private RateLimit rateLimit = new RateLimit();
    private List<String> allowlist = new ArrayList<>();
    private Login login = new Login();
    private RememberDevice rememberDevice = new RememberDevice();

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public List<String> getAllowlist() {
        return allowlist;
    }

    public void setAllowlist(List<String> allowlist) {
        this.allowlist = allowlist;
    }

    public Login getLogin() {
        return login;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    public RememberDevice getRememberDevice() {
        return rememberDevice;
    }

    public void setRememberDevice(RememberDevice rememberDevice) {
        this.rememberDevice = rememberDevice;
    }

    /**
     * Per-IP token-bucket settings: {@code capacity} (burst) refilled at {@code refillPerSecond}, plus the
     * {@link AutoBlock} escalation that turns a repeat throttler into a temporary blocklist entry.
     */
    public static class RateLimit {
        private int capacity = 120;
        private double refillPerSecond = 2.0;
        private AutoBlock autoBlock = new AutoBlock();

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        public AutoBlock getAutoBlock() {
            return autoBlock;
        }

        public void setAutoBlock(AutoBlock autoBlock) {
            this.autoBlock = autoBlock;
        }
    }

    /**
     * Auto-escalation of a repeat rate-limit offender into a temporary (auto) blocklist entry: an IP throttled
     * {@code breachThreshold} times within {@code windowSeconds} is auto-blocked for {@code blockSeconds}. Defaults
     * are 60 breaches / 60 s → 900 s block. An auto block is <b>never</b> persisted — it expires by its
     * own timer and is re-earned by a still-abusive IP after a restart.
     */
    public static class AutoBlock {
        private boolean enabled = true;
        private int breachThreshold = 60;
        private long windowSeconds = 60;
        private long blockSeconds = 900;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBreachThreshold() {
            return breachThreshold;
        }

        public void setBreachThreshold(int breachThreshold) {
            this.breachThreshold = breachThreshold;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public long getBlockSeconds() {
            return blockSeconds;
        }

        public void setBlockSeconds(long blockSeconds) {
            this.blockSeconds = blockSeconds;
        }
    }

    /**
     * Brute-force lockout + form-deterrent settings for the <b>application</b> realm (the operator login). This is
     * the app-scope source of truth {@link ProtectionConfigStore} exposes as a {@link ProtectionSettings}; the
     * per-site equivalent lives in {@code sites/<id>/plugins/protection.yml}.
     */
    public static class Login {
        private boolean honeypotEnabled = true;
        private int maxAttempts = ProtectionSettings.DEFAULT_MAX_ATTEMPTS;
        private long windowSeconds = ProtectionSettings.DEFAULT_WINDOW_SECONDS;
        private long lockoutSeconds = ProtectionSettings.DEFAULT_LOCKOUT_SECONDS;
        private Captcha captcha = new Captcha();

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

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public long getLockoutSeconds() {
            return lockoutSeconds;
        }

        public void setLockoutSeconds(long lockoutSeconds) {
            this.lockoutSeconds = lockoutSeconds;
        }

        public Captcha getCaptcha() {
            return captcha;
        }

        public void setCaptcha(Captcha captcha) {
            this.captcha = captcha;
        }
    }

    /**
     * "Remember this device" — lets a device that has logged in successfully (solving the CAPTCHA once) skip the
     * login CAPTCHA for {@code days} days, via a signed, per-realm/per-site, host-only cookie. Platform-security
     * floor concern, so it is app-level (never per-tenant): one toggle governs both realms. It only weakens the
     * CAPTCHA — never authentication — and only for a device that already proved valid credentials; the per-IP
     * lockout still applies to every attempt. Disabled ({@code enabled=false}) ⇒ the CAPTCHA is always shown.
     */
    public static class RememberDevice {
        private boolean enabled = true;
        private int days = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }
    }

    /**
     * App-realm CAPTCHA settings. The {@code secretKey} binds as the raw {@code ENC(...)} form (a
     * {@code secret}-typed setting, decrypted with {@code SecretCipher} at verification time).
     */
    public static class Captcha {
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
    }
}
