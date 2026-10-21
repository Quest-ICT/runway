package mx.quest.runway.wizard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import mx.quest.runway.security.SecurityHeaders;
import mx.quest.runway.endpoint.PreAuthBodyLimit;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The WIZARD-mode security chain. Present only before first-run setup completes, registered at the
 * <b>highest precedence</b> so it intercepts every request ahead of the (also-present but unreachable)
 * realm chains. It funnels everything to the open {@code /setup} surface: {@code /setup} and the health
 * probe are permitted, and any other path redirects to {@code /setup}. CSRF is disabled because setup is
 * pre-session (there are no accounts yet); once a superadmin is pinned, the relaunch comes up ACTIVE and
 * this whole layer is gone, so the open surface never coexists with real data.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(WizardModeCondition.class)
public class WizardSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain wizardChain(HttpSecurity http, PreAuthBodyLimit preAuthBodyLimit) throws Exception {
        http.securityMatcher("/**")
                // Nobody is signed in during setup: every body, the /setup form included, is held to the
                // pre-authentication budget (max-form-bytes) instead of the container's 16 MiB form limit.
                .addFilterBefore(preAuthBodyLimit.filter(), CsrfFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        // /__assets/** carries the self-hosted chrome (Tabler CSS, fonts, runway-chrome.js) the
                        // setup page styles itself with; without this it would 302 to /setup and render unstyled.
                        // These are static classpath resources — no secrets, safe pre-session.
                        .requestMatchers("/__assets/**", "/setup", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> response.sendRedirect("/setup")))
                .csrf(csrf -> csrf.disable()) // pre-session first-run setup — no session/token yet
                .headers(SecurityHeaders.baseline());
        return http.build();
    }
}
