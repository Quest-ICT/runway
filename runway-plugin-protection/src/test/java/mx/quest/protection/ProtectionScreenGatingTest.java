package mx.quest.protection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.AuthzExpressions;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The two screens are permission-gated: {@code /__app/protection} on the plugin's own
 * {@code protection|settings} control point, {@code /__admin/protection} on the kernel {@code CONFIGURE_SITE}
 * permission. Both {@code @PreAuthorize} through the kernel {@code authz} expression; a principal who fails the
 * check gets {@code 403}, one who passes reaches the controller (a {@code 3xx} redirect on POST). This proves
 * the gate is wired; the controllers enforce regardless of menu visibility.
 *
 * <p>The principal is placed on the {@link SecurityContextHolder} directly (rather than via
 * {@code spring-security-test}, which is not on this module's test classpath), which is exactly what
 * {@code @authz.may} reads.
 */
@SpringJUnitWebConfig(ProtectionScreenGatingTest.Config.class)
class ProtectionScreenGatingTest {

    private static final Permission APP_SETTINGS = new Permission("protection|settings", Realm.APPLICATION);
    private static final Permission CONFIGURE_SITE = new Permission("CONFIGURE_SITE", Realm.TENANT);

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AuthorizationService authorization;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Realm realm) {
        User user = new User("op1", "op", "", "Op", "", true, false, realm, Set.of(), List.of());
        RunwayUserDetails details = new RunwayUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, "n/a", details.getAuthorities()));
    }

    /** Asserts the action fails with a method-security access denial (unwrapping any servlet/nested wrapper). */
    private static void assertDenied(org.junit.jupiter.api.function.Executable action) {
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(Throwable.class, action);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.security.authorization.AuthorizationDeniedException) {
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("expected an AuthorizationDeniedException, got: " + thrown);
    }

    @Test
    void theAppScreenForbidsAPrincipalWithoutTheControlPoint() {
        signIn(Realm.APPLICATION);
        when(authorization.mayAccess(any(), eq(APP_SETTINGS), any())).thenReturn(false);
        // The @PreAuthorize gate denies before the controller runs — proven by the access-denied it raises. (In the
        // full app the security chain's ExceptionTranslationFilter maps this to a 403; here the bare method-security
        // context has no such filter, so the denial surfaces as the exception itself.) The app login policy is a
        // POST-only save now (the controls render as a panel on the plugin settings page, not a standalone GET).
        assertDenied(() -> mvc.perform(post("/__app/protection").param("maxAttempts", "5")));
    }

    @Test
    void theAppScreenAdmitsAPrincipalWithTheControlPoint() throws Exception {
        signIn(Realm.APPLICATION);
        when(authorization.mayAccess(any(), eq(APP_SETTINGS), any())).thenReturn(true);
        // A POST that passes the gate reaches the controller and redirects (no template render on POST).
        mvc.perform(post("/__app/protection")
                        .param("honeypotEnabled", "true").param("maxAttempts", "5")
                        .param("windowSeconds", "300").param("lockoutSeconds", "900"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void thePerSiteScreenForbidsAPrincipalWithoutConfigureSite() {
        signIn(Realm.TENANT);
        when(authorization.mayAccess(any(), eq(CONFIGURE_SITE), any())).thenReturn(false);
        assertDenied(() -> mvc.perform(get("/__admin/protection")));
    }

    @Test
    void thePerSiteScreenAdmitsAPrincipalWithConfigureSite() throws Exception {
        signIn(Realm.TENANT);
        when(authorization.mayAccess(any(), eq(CONFIGURE_SITE), any())).thenReturn(true);
        // The per-site form is honeypot + CAPTCHA (no lockout — that's app-scope); a passing gate reaches the save.
        mvc.perform(post("/__admin/protection")
                        .param("honeypotEnabled", "true").param("captchaEnabled", "false"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * A minimal web context: method security on, a permit-all chain (the {@code @PreAuthorize} is the gate under
     * test), the {@code authz} expression bean over a mocked {@link AuthorizationService}/{@link PermissionCatalog},
     * the two controllers + the store. No kernel security chains are needed — the request principal is placed on
     * the {@link SecurityContextHolder} by the test.
     */
    @Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Config {

        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        AuthorizationService authorizationService() {
            return mock(AuthorizationService.class);
        }

        @Bean
        PermissionCatalog permissionCatalog() {
            PermissionCatalog catalog = mock(PermissionCatalog.class);
            when(catalog.find("protection|settings")).thenReturn(Optional.of(APP_SETTINGS));
            when(catalog.find("CONFIGURE_SITE")).thenReturn(Optional.of(CONFIGURE_SITE));
            return catalog;
        }

        @Bean
        SiteResolver siteResolver() {
            Site site = mock(Site.class);
            when(site.id()).thenReturn("acme");
            SiteResolver resolver = mock(SiteResolver.class);
            when(resolver.current()).thenReturn(Optional.of(site));
            return resolver;
        }

        @Bean("authz")
        AuthzExpressions authz(AuthorizationService authz, PermissionCatalog catalog, SiteResolver siteResolver) {
            return new AuthzExpressions(authz, catalog, siteResolver);
        }

        @Bean
        ProtectionConfigStore store() {
            StorageProperties props = new StorageProperties();
            props.setExternal(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "runway-gating-"
                    + System.nanoTime()));
            StorageLayout layout = StorageLayout.from(props);
            return new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), noCipher());
        }

        @Bean
        AppProtectionController appController(ProtectionConfigStore store,
                @org.springframework.beans.factory.annotation.Qualifier("operatorAwareMessageSource")
                org.springframework.context.MessageSource messages) {
            return new AppProtectionController(store, messages);
        }

        @Bean
        SiteProtectionController siteController(ProtectionConfigStore store,
                @org.springframework.beans.factory.annotation.Qualifier("operatorAwareMessageSource")
                org.springframework.context.MessageSource messages) {
            return new SiteProtectionController(
                    () -> Optional.of(new mx.quest.runway.spi.model.SiteView("acme", "Acme",
                            java.util.Locale.ENGLISH, java.util.List.of(), null)), store, messages);
        }

        @Bean("operatorAwareMessageSource")
        org.springframework.context.MessageSource messages() {
            return new org.springframework.context.support.StaticMessageSource();
        }

        private static SecretCipher noCipher() {
            return new SecretCipher() {
                @Override public boolean isAvailable() { return false; }
                @Override public String encrypt(String plaintext) { throw new IllegalStateException("no key"); }
                @Override public String decrypt(String value) { return value; }
                @Override public boolean isEncrypted(String value) { return value != null && value.startsWith("ENC("); }
            };
        }
    }
}
