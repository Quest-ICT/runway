package mx.quest.runway.security;

import mx.quest.runway.authz.Permissions;
import mx.quest.runway.authz.RequestAuthorizers;
import mx.quest.runway.metrics.PrometheusScrapeAuthFilter;
import mx.quest.runway.web.AppRealmPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import mx.quest.runway.endpoint.PreAuthBodyLimit;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * The realm security chains. Two never-crossing realms are realized as two {@link SecurityFilterChain}
 * beans, the narrower application chain consulted first.
 *
 * <ul>
 *   <li><b>Application chain</b> (order 1, narrow matcher): {@code /__app/**}, {@code /app/login},
 *       {@code /app/logout}, {@code /actuator/**}. It authenticates <b>only</b> against the application
 *       user store (a chain-local {@link DaoAuthenticationProvider} over {@link AppUserDetailsService})
 *       and persists its {@code SecurityContext} under its own session key, so an application session
 *       and a tenant session can coexist in one browser without crossing.</li>
 *   <li><b>Tenant chain</b> (lowest order, no matcher): everything else — public site pages,
 *       {@code /login}, {@code /__admin} — handled by {@link TenantSecurityConfig}.</li>
 * </ul>
 *
 * <p>Authorization here is the <b>coarse</b> role band ({@link Roles}), with one fine-grained exception wired
 * as a {@code requirePermission} request gate: the Site templates editor ({@code /__app/site-templates/**})
 * requires the grantable {@code MANAGE_STYLES} permission. Other per-permission checks live in the controllers
 * (via {@link PlatformAuthority} or the method-security {@code authz} root). The chains run after the TENANT-band
 * site filters thanks to {@code spring.security.filter.order} (see {@code application.yml}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AppSecurityConfig {

    /** The session attribute under which the application realm stores its {@code SecurityContext}. */
    static final String APP_SECURITY_CONTEXT_KEY = "RUNWAY_APP_SECURITY_CONTEXT";

    /**
     * The session attribute under which the application realm stores its <b>CSRF token</b> — distinct from the
     * tenant realm's ({@code RUNWAY_SITE_CSRF_TOKEN}), so the two realms have <b>independent</b> tokens. Both
     * realms share one {@code HttpSession} (they coexist in one browser via distinct {@link #APP_SECURITY_CONTEXT_KEY
     * SecurityContext} keys); if they also shared the <em>default</em> single CSRF attribute, a login/logout in one
     * realm — which rotates the CSRF token (session-fixation defense) — would silently invalidate an open form in
     * the <em>other</em> realm's tab, producing a spurious 403. Keying the CSRF token per realm completes that
     * isolation. (Not a security hole — CSRF being over-strict fails safe — but a real cross-tab papercut.)
     */
    static final String APP_CSRF_TOKEN_KEY = "RUNWAY_APP_CSRF_TOKEN";

    @Bean
    @Order(1)
    public SecurityFilterChain appRealmChain(HttpSecurity http, AppUserDetailsService appUsers,
                                             AppUserStore appUserStore,
                                             PasswordEncoder passwordEncoder,
                                             LoginAttemptGate loginAttemptGate,
                                             LoginFormGuard loginFormGuard,
                                             TrustedDeviceIssuer trustedDeviceIssuer,
                                             RequestAuthorizers requestAuthorizers,
                                             PrometheusScrapeAuthFilter scrapeAuthFilter,
                                             PreAuthBodyLimit preAuthBodyLimit) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(appUsers);
        provider.setPasswordEncoder(passwordEncoder);
        AccountStatusChecks.verifyPasswordFirst(provider); // a suspended account costs a hash like any other

        HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
        contextRepository.setSpringSecurityContextKey(APP_SECURITY_CONTEXT_KEY);

        HttpSessionCsrfTokenRepository csrfRepository = new HttpSessionCsrfTokenRepository();
        csrfRepository.setSessionAttributeName(APP_CSRF_TOKEN_KEY); // realm-private CSRF token (see the constant)

        // The realm's paths have one definition (AppRealmPaths), shared with every filter that exempts or guards the
        // application realm, so this matcher and those filters cannot drift apart.
        http.securityMatcher(AppRealmPaths.securityMatchers())
                // A session's principal is re-checked against app-users.yml on every request: a suspended or
                // deleted operator is signed out of this realm at once, and a tier change applies at once. It runs
                // FIRST — before the body budget decides "signed in" — so a suspended operator's open session is not
                // handed the signed-in body limit. Same-anchor filters keep registration order.
                .addFilterBefore(new AccountStateFilter(
                                principal -> appUserStore.findByPrincipalId(principal.principalId()),
                                contextRepository),
                        CsrfFilter.class)
                // Not signed in: the body is held to the pre-authentication budget before the CSRF check reads it.
                .addFilterBefore(preAuthBodyLimit.filter(), CsrfFilter.class)
                .addFilterBefore(new LoginProtectionFilter("/app/login", loginAttemptGate, loginFormGuard,
                                ProtectionScope.Realm.APPLICATION, null),
                        UsernamePasswordAuthenticationFilter.class)
                // The password-recovery REQUEST form gets the same honeypot/CAPTCHA guard (stops automated
                // reset-email flooding) but NOT the per-IP lockout — a user locked out of login still needs it.
                .addFilterBefore(new LoginProtectionFilter("/app/forgot-password", loginAttemptGate, loginFormGuard,
                                ProtectionScope.Realm.APPLICATION, null, false),
                        UsernamePasswordAuthenticationFilter.class)
                // A monitoring system may scrape /actuator/prometheus with a bearer token (when configured);
                // this establishes the ROLE_METRICS_SCRAPE authority the rule below permits. Inert with no token.
                .addFilterBefore(scrapeAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authenticationProvider(provider)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/app/login").permitAll()
                        // Self-service password recovery is public (a user is by definition not signed in).
                        .requestMatchers("/app/forgot-password", "/app/reset").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole(Roles.SYSTEM_SUPERADMIN,
                                Roles.SYSTEM_ADMIN, PrometheusScrapeAuthFilter.SCRAPE_ROLE)
                        .requestMatchers("/actuator/**").hasAnyRole(Roles.SYSTEM_SUPERADMIN, Roles.SYSTEM_ADMIN)
                        // The Site templates (seed) editor is gated by the grantable MANAGE_STYLES permission,
                        // not just the coarse role band — so a non-master operator can be granted it in isolation.
                        .requestMatchers("/__app/site-templates/**")
                        .access(requestAuthorizers.requirePermission(Permissions.MANAGE_STYLES))
                        // Editing a site's routing hosts is gated by the grantable MANAGE_SITE_HOSTS permission,
                        // so it can be delegated to a role/user without the master-only tenancy lifecycle rights.
                        .requestMatchers(HttpMethod.POST, "/__app/sites/*/hosts")
                        .access(requestAuthorizers.requirePermission(Permissions.MANAGE_SITE_HOSTS))
                        // The operator audit journal (viewer + CSV export) names who did what to every site and
                        // account, so it is gated by the grantable VIEW_AUDIT permission, not the coarse role
                        // band: the master and wildcard admins pass; an ordinary operator only once granted it.
                        .requestMatchers("/__app/logs", "/__app/logs/**")
                        .access(requestAuthorizers.requirePermission(Permissions.VIEW_AUDIT))
                        .anyRequest().hasAnyRole(Roles.SYSTEM_SUPERADMIN, Roles.SYSTEM_ADMIN, Roles.SYSTEM_OPERATOR))
                .formLogin(form -> form
                        .loginPage("/app/login")
                        .loginProcessingUrl("/app/login")
                        // Same always-redirect-to-/__app as defaultSuccessUrl(…, true), but via a success handler
                        // so a "remember this device" cookie can be minted before the redirect (CAPTCHA skip).
                        .successHandler(new TrustedDeviceSuccessHandler(trustedDeviceIssuer,
                                ProtectionScope.Realm.APPLICATION, null, "/__app"))
                        .failureUrl("/app/login?error"))
                .logout(logout -> logout
                        .logoutUrl("/app/logout")
                        .logoutSuccessUrl("/app/login?logout")
                        .invalidateHttpSession(false)) // leave a coexisting tenant session intact
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).ignoringRequestMatchers("/actuator/**"))
                // A stale CSRF token (a long-open form, or a restart) renders a friendly "sign in again" page
                // instead of a bare 403; a real permission denial still falls through to the normal error page.
                .exceptionHandling(ex -> ex.accessDeniedHandler(new CsrfExpiryAccessDeniedHandler("/app/login")))
                .headers(SecurityHeaders.baseline());
        return http.build();
    }
}
