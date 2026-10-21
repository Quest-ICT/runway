package mx.quest.runway.security;

import mx.quest.runway.endpoint.AnonymousRouteMatcher;
import mx.quest.runway.endpoint.EndpointContributorRegistry;
import mx.quest.runway.endpoint.PreAuthBodyLimit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import mx.quest.runway.site.SiteResolver;

/**
 * The tenant realm chain — the catch-all (lowest order), consulted after the application chain. It
 * authenticates <b>only</b> against the tenant identity stores via a chain-local
 * {@link DaoAuthenticationProvider} over {@link SiteUserDetailsService} (which scopes every login to
 * the bound site, the tenant-isolation point), and persists its {@code SecurityContext} under a
 * <b>different</b> session key than the application chain.
 *
 * <p>That distinct key is the dual-session mechanism: an application session and a tenant session can
 * coexist in one browser without crossing, and logging out of one realm
 * ({@code invalidateHttpSession(false)}) leaves the other intact. An application-authenticated browser
 * hitting {@code /__admin} is therefore <em>not</em> a tenant — it is bounced to the tenant login.
 *
 * <p>Authorization is the coarse role band only; {@code /login} is public, the tenant admin surface
 * requires a tenant role, and everything else (the public site pages) is anonymous.
 */
@Configuration(proxyBeanMethods = false)
public class TenantSecurityConfig {

    /**
     * The <b>base</b> session-attribute key for the tenant realm's {@code SecurityContext}. The actual key is
     * per site — {@code RUNWAY_SITE_SECURITY_CONTEXT::<siteId>} (see {@link SiteScopedSecurityContextRepository})
     * — distinct from the application realm's single {@code RUNWAY_APP_SECURITY_CONTEXT}. Per-site keying lets
     * independent site logins coexist in one browser even under a shared cookie {@code Domain}.
     */
    static final String SITE_SECURITY_CONTEXT_KEY = "RUNWAY_SITE_SECURITY_CONTEXT";

    /**
     * The <b>base</b> session attribute for the tenant realm's <b>CSRF token</b>, keyed per site
     * ({@code RUNWAY_SITE_CSRF_TOKEN::<siteId>} — see {@link SiteScopedCsrfTokenRepository}) and distinct from the
     * application realm's {@code RUNWAY_APP_CSRF_TOKEN}. So no login/logout in another realm <em>or another
     * site</em> can invalidate an open form's token — completing the per-realm-and-per-site CSRF isolation that
     * matches the {@link #SITE_SECURITY_CONTEXT_KEY SecurityContext} separation.
     */
    static final String SITE_CSRF_TOKEN_KEY = "RUNWAY_SITE_CSRF_TOKEN";

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain tenantRealmChain(HttpSecurity http, SiteUserDetailsService siteUsers,
                                                PasswordEncoder passwordEncoder,
                                                TenantSiteAuthorizationManager boundToCurrentTenancy,
                                                LoginAttemptGate loginAttemptGate,
                                                LoginFormGuard loginFormGuard,
                                                TrustedDeviceIssuer trustedDeviceIssuer,
                                                SiteResolver siteResolver,
                                                SiteUserStore siteUserStore,
                                                TenantMasterStore tenantMasters,
                                                ObjectProvider<EndpointContributorRegistry> endpointRoutes,
                                                PreAuthBodyLimit preAuthBodyLimit)
            throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(siteUsers);
        provider.setPasswordEncoder(passwordEncoder);
        AccountStatusChecks.verifyPasswordFirst(provider); // a suspended account costs a hash like any other

        // Per-realm AND per-site session keys: the tenant SecurityContext and CSRF token are stored under
        // <base>::<siteId>, so distinct sites (and the app realm) never share either — even in one session.
        SiteScopedSecurityContextRepository contextRepository =
                new SiteScopedSecurityContextRepository(siteResolver, SITE_SECURITY_CONTEXT_KEY);
        SiteScopedCsrfTokenRepository csrfRepository =
                new SiteScopedCsrfTokenRepository(siteResolver, SITE_CSRF_TOKEN_KEY);

        // A session's principal is re-checked on every request against the bound site's users, or the tenant-master
        // store plus the master's assignment to this site: suspended, deleted or unassigned means signed out of this
        // site at once, and a tier change applies at once. It runs FIRST — before the body budget below decides
        // "signed in" — so a suspended account's open session is not handed the signed-in body limit.
        // Filters registered before the same anchor keep their registration order.
        http.addFilterBefore(new AccountStateFilter(principal -> siteResolver.current().flatMap(site ->
                                siteUserStore.findByPrincipalId(site.id(), principal.principalId())
                                        .or(() -> tenantMasters.findByPrincipalId(principal.principalId())
                                                .filter(master -> master.assignedTo(site.id())))),
                                contextRepository),
                        CsrfFilter.class)
                // A visitor who is not signed in may send at most the pre-authentication budget (max-form-bytes) —
                // checked before the CSRF check, the first reader of a form body, would parse it.
                .addFilterBefore(preAuthBodyLimit.filter(), CsrfFilter.class)
                .addFilterBefore(new LoginProtectionFilter("/login", loginAttemptGate, loginFormGuard,
                                ProtectionScope.Realm.TENANT, siteResolver),
                        UsernamePasswordAuthenticationFilter.class)
                // The password-recovery REQUEST form gets the honeypot/CAPTCHA guard for the bound site's scope,
                // but NOT the per-IP lockout — a user locked out of the site login still needs recovery to work.
                .addFilterBefore(new LoginProtectionFilter("/forgot-password", loginAttemptGate, loginFormGuard,
                                ProtectionScope.Realm.TENANT, siteResolver, false),
                        UsernamePasswordAuthenticationFilter.class)
                .authenticationProvider(provider)
                // /__csp-report is CSRF-exempt: browsers POST CSP violation reports automatically, with no
                // session and no CSRF token (see CspReportController). It falls into this catch-all chain's
                // anyRequest().permitAll() below, so it needs no auth — only this CSRF ignore to reach the
                // controller. It is a reserved kernel prefix, so no plugin/site can claim it.
                //
                // Anonymous plugin form routes (SPI 0.5.0, Route.anonymous) are CSRF-exempt too: a public form on a
                // cacheable page (contact) cannot carry a per-session token. The matcher exempts exactly a POST to a
                // registered anonymous route on a bound site — nothing else — and the endpoint dispatcher runs such a
                // route with NO principal and a form body only, so a forged cross-site submission cannot act as the
                // signed-in visitor. See AnonymousRouteMatcher and EndpointDispatchFilter.
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .ignoringRequestMatchers("/__csp-report")
                        .ignoringRequestMatchers(new AnonymousRouteMatcher(endpointRoutes, siteResolver)))
                // Friendly "sign in again" page on a stale CSRF token (see AppSecurityConfig); a real
                // permission/tenancy denial still falls through to the site's own error page.
                .exceptionHandling(ex -> ex.accessDeniedHandler(new CsrfExpiryAccessDeniedHandler("/login")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/logout").permitAll()
                        // Self-service password recovery on the site's host (public — the user isn't signed in).
                        .requestMatchers("/forgot-password", "/reset").permitAll()
                        // Not role alone: the principal must be bound to the CURRENT tenancy (closes the
                        // cross-tenant session-replay vector — a session is valid only for its own site).
                        .requestMatchers("/__admin/**").access(boundToCurrentTenancy)
                        .anyRequest().permitAll()) // public site pages are anonymous
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        // Same always-redirect-to-/__admin as defaultSuccessUrl(…, true), but via a success handler
                        // that can mint a per-site "remember this device" cookie (scoped to the resolved site).
                        .successHandler(new TrustedDeviceSuccessHandler(trustedDeviceIssuer,
                                ProtectionScope.Realm.TENANT, siteResolver, "/__admin"))
                        .failureUrl("/login?error"))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(false)) // leave a coexisting application session intact
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .headers(SecurityHeaders.baseline());
        return http.build();
    }
}
