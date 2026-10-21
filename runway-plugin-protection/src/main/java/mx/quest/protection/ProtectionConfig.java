package mx.quest.protection;

import mx.quest.runway.console.AssetVersion;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.protect.EdgeGuard;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.LoginFormGuard;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CidrSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The plugin's {@code mainClass} — registered into the kernel context by the plugin loader before refresh, so
 * its {@code @Primary} beans <b>win by-type injection</b> over the kernel's permissive defaults and arm the
 * security floor: {@code EdgeRejectFilter} now enforces the rate limit; {@code LoginProtectionFilter} now
 * enforces the per-IP lockout ({@link LoginAttemptGate}) <b>and</b> the request-aware honeypot/CAPTCHA form
 * guard ({@link LoginFormGuard}). It declares itself essential in {@code plugin.yml}; the kernel honors that only
 * because the id {@code protection} is on the security-floor allowlist.
 *
 * <p>It also registers a {@link CaptchaLoginViewContributor} interceptor (via the {@link WebMvcConfigurer} below)
 * that feeds the kernel's generic login-view captcha slot the public site key + provider flags when a scope's
 * captcha is active — so the login page renders the widget without any provider knowledge in the kernel.
 *
 * <p>The {@link ProtectionConfigStore} it wires reads app-scope thresholds from {@link ProtectionProperties} and
 * per-site config from {@code sites/<id>/plugins/protection.yml} (encrypting the captcha secret via the kernel's
 * {@link SecretCipher}); the login gate reads its thresholds from that store on every check, so config changes
 * apply without a restart.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProtectionProperties.class)
public class ProtectionConfig {

    /**
     * The mutable IP blocklist (manual bans persisted to {@code external/blocklist.yml}; auto bans in-memory only).
     * Its allowlist is the configured trusted CIDRs plus loopback (always) — those are never blockable. Consulted
     * on every request by the edge guard and escalated into by the rate limiter; edited by the {@code /__app/dos}
     * screen.
     */
    @Bean
    public IpBlocklist protectionIpBlocklist(ProtectionProperties properties, StorageLayout layout,
                                             YamlStore yamlStore) {
        return new IpBlocklist(layout, yamlStore, CidrSet.of(properties.getAllowlist()));
    }

    /**
     * The per-IP token-bucket rate limiter with auto-escalation into the {@link IpBlocklist}. Exposed as its own
     * bean (not created inline in the guard) so the {@code /__app/dos} screen can read its {@code topOffenders}.
     */
    @Bean
    public TokenBucketRateLimiter protectionRateLimiter(ProtectionProperties properties, IpBlocklist blocklist) {
        ProtectionProperties.RateLimit rateLimit = properties.getRateLimit();
        ProtectionProperties.AutoBlock ab = rateLimit.getAutoBlock();
        return new TokenBucketRateLimiter(
                rateLimit.getCapacity(), rateLimit.getRefillPerSecond(), 100_000, System::nanoTime,
                blocklist,
                new TokenBucketRateLimiter.AutoBlock(ab.isEnabled(), ab.getBreachThreshold(), ab.getWindowSeconds(),
                        ab.getBlockSeconds()),
                System::currentTimeMillis);
    }

    @Bean
    @Primary
    public EdgeGuard protectionEdgeGuard(ProtectionProperties properties, IpBlocklist blocklist,
                                         TokenBucketRateLimiter limiter) {
        return new RateLimitEdgeGuard(CidrSet.of(properties.getAllowlist()), blocklist, limiter);
    }

    /**
     * The per-scope protection config engine (app-level {@link ProtectionProperties} + per-site
     * {@code protection.yml}). Injected by the honeypot guard and the login gate; also the store the
     * dedicated Protection screens edit.
     */
    @Bean
    public ProtectionConfigStore protectionConfigStore(ProtectionProperties properties, StorageLayout layout,
                                                       YamlStore yamlStore, SecretCipher secretCipher) {
        return new ProtectionConfigStore(properties, layout, yamlStore, secretCipher);
    }

    @Bean
    @Primary
    public LoginAttemptGate protectionLoginGate(ProtectionConfigStore configStore) {
        // Thresholds come from the app-scope settings, read fresh on each check (config-driven, no restart needed).
        return new SlidingWindowLoginGate(configStore::appSettings, System::currentTimeMillis);
    }

    /** Verifies a CAPTCHA token server-side against the provider's {@code siteverify} endpoint (fail-open on outage). */
    @Bean
    public CaptchaVerifier protectionCaptchaVerifier() {
        return new CaptchaVerifier();
    }

    /**
     * Allowlists the CAPTCHA providers' domains in the kernel CSP (collected by type via {@code CspHostContributor}),
     * so a challenge's script/iframe/verification calls are permitted and a strict enforce-mode CSP can coexist
     * with a configured CAPTCHA. Kernel stays provider-agnostic.
     */
    @Bean
    public mx.quest.runway.web.CspHostContributor protectionCaptchaCspContributor() {
        return new CaptchaCspContributor();
    }

    /**
     * The reusable site-form CAPTCHA seam ({@link mx.quest.runway.spi.CaptchaService}), armed from each site's own
     * per-site CAPTCHA settings. {@code @Primary} so it wins by-type injection over the kernel's permissive
     * {@code NoCaptchaService} default — letting the contact form (and any plugin/page form) render + verify a
     * challenge exactly when the tenant has configured one, with no coupling to this plugin.
     */
    @Bean
    @Primary
    public CaptchaWidgetService protectionCaptchaWidgetService(ProtectionConfigStore configStore,
                                                              CaptchaVerifier captchaVerifier,
                                                              ObjectProvider<AssetVersion> assetVersion) {
        // The kernel's asset token, so the widget's /__assets/captcha-v3.js reference is versioned like the kernel's
        // (absent only in a partial context, where the reference stays unversioned and is served no-cache).
        return new CaptchaWidgetService(configStore, captchaVerifier, assetVersion.getIfAvailable());
    }

    /**
     * The "remember this device" policy — the {@code @Primary} {@link mx.quest.runway.security.TrustedDeviceIssuer}
     * that mints a signed trusted-device cookie on a successful login (opt-in) and the matching trust check the
     * login form guard + view contributor use to skip the CAPTCHA on that device. App-level feature flag; the
     * cookie is AES-GCM sealed with the kernel {@link SecretCipher} and scoped per realm/site. Wins by-type
     * injection over the kernel's no-op default; failure degrades safely to "CAPTCHA always shown".
     */
    @Bean
    @Primary
    public TrustedDeviceService protectionTrustedDeviceService(ProtectionProperties properties,
                                                              ProtectionConfigStore configStore,
                                                              SecretCipher secretCipher) {
        return new TrustedDeviceService(properties, configStore, secretCipher);
    }

    @Bean
    @Primary
    public LoginFormGuard protectionLoginFormGuard(ProtectionConfigStore configStore, CaptchaVerifier captchaVerifier,
                                                   TrustedDeviceService trustedDeviceService) {
        return new LoginFormPolicyGuard(configStore, captchaVerifier, trustedDeviceService);
    }

    /**
     * The app-realm rescue seam ({@link mx.quest.runway.spi.SiteLoginProtectionControl}) that lets an operator
     * switch a locked-out site's login CAPTCHA off from the Sites screen. {@code @Primary} so it wins by-type
     * injection over the kernel's no-op default; reads and rewrites the per-site config over the store above.
     */
    @Bean
    @Primary
    public mx.quest.runway.spi.SiteLoginProtectionControl protectionSiteLoginControl(ProtectionConfigStore configStore) {
        return new ProtectionSiteLoginControl(configStore);
    }

    /**
     * The login-view captcha contributor, registered as a {@link WebMvcConfigurer} so the kernel's MVC picks it up
     * from this plugin {@code @Configuration} (collected by type, like {@code PluginStaticResourceConfig}). It maps
     * the interceptor only to the two login-view GETs; every other request is untouched.
     */
    @Bean
    public WebMvcConfigurer protectionCaptchaViewConfigurer(ProtectionConfigStore configStore,
                                                            SiteResolver siteResolver,
                                                            TrustedDeviceService trustedDeviceService) {
        CaptchaLoginViewContributor contributor =
                new CaptchaLoginViewContributor(configStore, siteResolver, trustedDeviceService);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                // Both realms' login pages AND their password-recovery REQUEST pages (so a configured challenge
                // also renders on the forgot-password form).
                registry.addInterceptor(contributor)
                        .addPathPatterns("/app/login", "/login", "/app/forgot-password", "/forgot-password");
            }
        };
    }

    // --- Dedicated custom Protection screens -----------------------------------------------
    // Full operator screens rendered inside the kernel op-layout shell: each screen controller carries the SPI's
    // @ConsoleScreen, so the kernel's ConsoleScreenAdvice fills the shell model (SPI 0.6.0; the plugin's own
    // ProtectionShellAdvice is gone). Registered as explicit @Beans (plugins wire by hand, never by component scan);
    // Spring MVC still detects the @Controller annotations on the returned classes and maps them like kernel ones.

    /**
     * The save handler for the app-scope login policy — the POST behind the login-protection <b>panel</b> that
     * renders on the plugin's own settings page ({@code /__app/plugins/protection/settings}); edits the app scope.
     */
    @Bean
    public AppProtectionController appProtectionController(ProtectionConfigStore configStore,
                                                          @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        return new AppProtectionController(configStore, messages);
    }

    /**
     * The read-model the app login-protection panel ({@code templates/admin-app.html}) binds to via
     * {@code ${@appProtectionPanel.*}} — the kernel-owned plugin-settings page does not populate the plugin's model,
     * so the panel pulls the live app-scope settings through this bean. The bean name is the SpEL reference.
     */
    @Bean
    public AppProtectionPanel appProtectionPanel(ProtectionConfigStore configStore) {
        return new AppProtectionPanel(configStore);
    }

    /** The tenant-realm Protection screen ({@code /__admin/protection}); edits the bound site's per-site policy. */
    @Bean
    public SiteProtectionController siteProtectionController(RequestContext requestContext,
                                                            ProtectionConfigStore configStore,
                                                            @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        return new SiteProtectionController(requestContext, configStore, messages);
    }

    /**
     * The application-realm <b>DoS activity</b> screen ({@code /__app/dos}): live rate-limit offenders + the
     * mutable blocklist (block/unblock). App-level platform concern — never a per-tenant surface.
     */
    @Bean
    public DosController dosController(TokenBucketRateLimiter limiter, IpBlocklist blocklist,
                                       @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        return new DosController(limiter, blocklist, messages);
    }
}
