package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.protect.EdgeGuard;
import mx.quest.runway.protect.EdgeRequest;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.LoginFormGuard;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * The plugin's {@code @Configuration} (what the loader registers) supplies working {@code @Primary}
 * {@link EdgeGuard}, {@link LoginAttemptGate} and {@link LoginFormGuard} beans — so once loaded they arm the
 * kernel's security floor, binding their thresholds from {@code runway.plugin.protection.*}. The kernel storage
 * beans it depends on ({@link StorageLayout}, {@link YamlStore}, {@link SecretCipher}) are supplied here as the
 * host would at runtime.
 */
@SpringJUnitConfig({ProtectionConfig.class, ProtectionConfigTest.HostBeans.class})
@TestPropertySource(properties = {
        "runway.plugin.protection.rate-limit.capacity=2",
        "runway.plugin.protection.rate-limit.refill-per-second=0",
        "runway.plugin.protection.login.max-attempts=1"
})
class ProtectionConfigTest {

    @Autowired
    private EdgeGuard edgeGuard;

    @Autowired
    private LoginAttemptGate loginGate;

    @Autowired
    private LoginFormGuard loginFormGuard;

    @Test
    void theConfigSuppliesTheRealPolicyGuards() {
        assertInstanceOf(RateLimitEdgeGuard.class, edgeGuard, "the @Primary EdgeGuard is the rate limiter");
        assertInstanceOf(SlidingWindowLoginGate.class, loginGate, "the @Primary LoginAttemptGate is the lockout");
        assertInstanceOf(LoginFormPolicyGuard.class, loginFormGuard,
                "the @Primary LoginFormGuard is the honeypot + captcha policy guard");
    }

    @Test
    void theBoundThresholdsAreEnforced() {
        EdgeRequest request = new EdgeRequest("9.9.9.9", "GET", "/");
        assertTrue(edgeGuard.check(request).allowed());
        assertTrue(edgeGuard.check(request).allowed());
        assertFalse(edgeGuard.check(request).allowed(), "capacity=2 from settings is exhausted → rejected");

        loginGate.recordFailure("9.9.9.9");
        assertTrue(loginGate.isLocked("9.9.9.9"), "max-attempts=1 from settings → locked after one failure");
    }

    /** The kernel-provided storage/crypto beans the plugin config injects (supplied by the host at runtime). */
    @Configuration(proxyBeanMethods = false)
    static class HostBeans {

        @Bean
        StorageLayout storageLayout() {
            return StorageLayout.from(new StorageProperties());
        }

        @Bean
        YamlStore yamlStore() {
            return new YamlStore();
        }

        @Bean
        SiteResolver siteResolver(StorageLayout layout, YamlStore yaml) {
            // The kernel bean the plugin's login-view captcha configurer injects; a real resolver over an empty
            // storage tree (no site files) is enough — the config test never renders a login view.
            return new SiteResolver(new SiteStore(layout, yaml), new PlatformSettingsStore(layout, yaml));
        }

        @Bean
        mx.quest.runway.spi.service.RequestContext requestContext() {
            // The SPI request context the tenant Protection screen reads its bound site from; the host provides it
            // at runtime. No request is bound in this test, so it answers empty (as the kernel's does off-request).
            return java.util.Optional::empty;
        }

        @Bean("operatorAwareMessageSource")
        org.springframework.context.MessageSource operatorAwareMessageSource() {
            // The console message source the Protection screen controllers inject (@Qualifier); host-provided at runtime.
            return new org.springframework.context.support.StaticMessageSource();
        }

        @Bean
        SecretCipher secretCipher() {
            // A no-op cipher: unavailable (no key), pass-through decrypt. Sufficient for the app-scope path here.
            return new SecretCipher() {
                @Override
                public boolean isAvailable() {
                    return false;
                }

                @Override
                public String encrypt(String plaintext) {
                    throw new IllegalStateException("no key");
                }

                @Override
                public String decrypt(String value) {
                    return value;
                }

                @Override
                public boolean isEncrypted(String value) {
                    return value != null && value.startsWith("ENC(");
                }
            };
        }
    }
}
