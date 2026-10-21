package mx.quest.protection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.AuthzExpressions;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CidrSet;
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
 * The DoS screen is gated on the plugin's own {@code protection|settings} control point (app realm): a principal
 * who fails the check is denied on GET and on both POSTs; one who passes reaches the controller (a {@code 3xx}
 * redirect). Proves the gate is wired and that block/unblock are reachable when authorized.
 */
@SpringJUnitWebConfig(DosScreenGatingTest.Config.class)
class DosScreenGatingTest {

    private static final Permission APP_SETTINGS = new Permission("protection|settings", Realm.APPLICATION);

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

    private static void signIn() {
        User user = new User("op1", "op", "", "Op", "", true, false, Realm.APPLICATION, Set.of(), List.of());
        RunwayUserDetails details = new RunwayUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, "n/a", details.getAuthorities()));
    }

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
    void theScreenForbidsAPrincipalWithoutTheControlPoint() {
        signIn();
        when(authorization.mayAccess(any(), eq(APP_SETTINGS), any())).thenReturn(false);
        assertDenied(() -> mvc.perform(get("/__app/dos")));
        assertDenied(() -> mvc.perform(post("/__app/dos/block").param("ip", "203.0.113.7")));
        assertDenied(() -> mvc.perform(post("/__app/dos/unblock").param("ip", "203.0.113.7")));
    }

    @Test
    void aManualBlockAndUnblockRoundTripWhenAuthorized() throws Exception {
        signIn();
        when(authorization.mayAccess(any(), eq(APP_SETTINGS), any())).thenReturn(true);

        IpBlocklist blocklist = context.getBean(IpBlocklist.class);

        mvc.perform(post("/__app/dos/block").param("ip", "203.0.113.7").param("seconds", "900")
                        .param("reason", "abuse").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }))
                .andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertTrue(blocklist.isBlocked("203.0.113.7"),
                "the authorized block reached the controller and blocked the IP");

        mvc.perform(post("/__app/dos/unblock").param("ip", "203.0.113.7"))
                .andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertFalse(blocklist.isBlocked("203.0.113.7"),
                "the authorized unblock removed the IP");
    }

    @Test
    void blockingYourOwnGatedIpIsRefused() throws Exception {
        signIn();
        when(authorization.mayAccess(any(), eq(APP_SETTINGS), any())).thenReturn(true);
        IpBlocklist blocklist = context.getBean(IpBlocklist.class);

        mvc.perform(post("/__app/dos/block").param("ip", "10.9.9.9")
                        .with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }))
                .andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertFalse(blocklist.isBlocked("10.9.9.9"),
                "the controller refuses to block the operator's own address");
    }

    /** A minimal web context: method security on, permit-all chain, the authz expression, the DoS controller. */
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
        IpBlocklist blocklist() {
            Path file = Path.of(System.getProperty("java.io.tmpdir"),
                    "runway-dos-gating-" + System.nanoTime() + ".yml");
            return new IpBlocklist(file, new YamlStore(), CidrSet.of(List.of()),
                    () -> System.currentTimeMillis() / 1000L);
        }

        @Bean
        TokenBucketRateLimiter limiter(IpBlocklist blocklist) {
            return new TokenBucketRateLimiter(120, 2.0, 1_000, () -> 0L, blocklist,
                    new TokenBucketRateLimiter.AutoBlock(false, 60, 60, 900), () -> 0L);
        }

        @Bean
        DosController dosController(TokenBucketRateLimiter limiter, IpBlocklist blocklist,
                @org.springframework.beans.factory.annotation.Qualifier("operatorAwareMessageSource")
                org.springframework.context.MessageSource messages) {
            return new DosController(limiter, blocklist, messages);
        }

        @Bean("operatorAwareMessageSource")
        org.springframework.context.MessageSource messages() {
            return new org.springframework.context.support.StaticMessageSource();
        }
    }
}
