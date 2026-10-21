package mx.quest.runway.security;

import java.util.Locale;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The shared security configuration: the single, realm-agnostic Argon2id {@link PasswordEncoder}
 * bean (the realms' separation lives in the stores and chains, not the hashing) and the
 * {@code runway.security.*} property binding. The realm security chains are configured separately as
 * each realm is built (slices 5c/5f).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunwaySecurityProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * The application's password encoder — one Argon2id encoder, concurrency-capped so a login flood
     * degrades gracefully rather than exhausting memory.
     *
     * <p>The memory-cost profile is the operator's platform override ({@code platform.yml} →
     * {@code passwordStrength}, set on the app-realm Settings page) when present, else the
     * {@code runway.security.password-strength} config default. Read once here at startup — a profile change
     * applies to <b>new</b> hashes on the next launch; existing PHC hashes verify regardless of the active
     * profile (they are self-describing).
     */
    @Bean
    public PasswordEncoder passwordEncoder(RunwaySecurityProperties properties, ActivePasswordStrength active) {
        return PasswordEncoders.argon2idLimited(active.strength(),
                properties.getMaxConcurrentHashes(), properties.getAcquireTimeoutMillis());
    }

    /** The profile chosen once at startup, shared by the encoder and the About page so they cannot disagree. */
    @Bean
    public ActivePasswordStrength activePasswordStrength(RunwaySecurityProperties properties,
                                                         PlatformSettingsStore platform) {
        return new ActivePasswordStrength(effectiveStrength(properties, platform));
    }

    /** The platform override if set and valid, otherwise the config default. A bad override is logged, not fatal. */
    private static PasswordStrength effectiveStrength(RunwaySecurityProperties properties, PlatformSettingsStore platform) {
        String override = platform.get().strength().orElse(null);
        if (override != null) {
            try {
                return PasswordStrength.valueOf(override.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException bad) {
                log.warn("Ignoring invalid platform.yml passwordStrength '{}' — using the config default {}",
                        override, properties.getPasswordStrength());
            }
        }
        return properties.getPasswordStrength();
    }
}
