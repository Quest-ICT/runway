package mx.quest.runway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link TrustedForwardedHeaderFilter} as the outermost filter ({@link FilterOrder#CORE_FORWARDED}).
 * Parses {@code runway.proxy.trusted-cidrs} once at startup, warning the operator about any malformed entry
 * (rather than silently shrinking the trust set) and about an empty resolved set while forwarded handling is on
 * (every request then attributed to its direct peer), and about a catch-all ({@link CidrSet#hasCatchAll}: a {@code /0},
 * or ranges that together cover every IPv4 or every IPv6 address), which lets any client fake its address (the Server
 * settings form refuses one; a value from {@code application.yml} is only warned about).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunwayProxyProperties.class)
public class ForwardedHeaderConfig {

    private static final Logger log = LoggerFactory.getLogger(ForwardedHeaderConfig.class);

    @Bean
    public FilterRegistrationBean<TrustedForwardedHeaderFilter> trustedForwardedHeaderFilterRegistration(
            RunwayProxyProperties properties) {
        CidrSet.Parsed parsed = CidrSet.parse(properties.getTrustedCidrs());
        if (!parsed.rejected().isEmpty()) {
            log.warn("ignoring malformed runway.proxy.trusted-cidrs entries: {}", parsed.rejected());
        }
        if (properties.isForwardedHeaders() && parsed.set().isEmpty()) {
            log.warn("runway.proxy.forwarded-headers is enabled but trusted-cidrs resolved to empty — "
                    + "X-Forwarded-* will be IGNORED from every peer; each request is attributed to its direct connection");
        }
        // Warn, never refuse: a value from application.yml or an older platform.yml must not stop the server.
        if (properties.isForwardedHeaders() && parsed.set().hasCatchAll()) {
            log.warn("runway.proxy.trusted-cidrs covers every address of a family (a /0, or ranges that add up to "
                    + "one): X-Forwarded-* is honoured from EVERY "
                    + "peer, so any client can fake its address for rate limits, lockouts and logs — list only "
                    + "your proxy's address");
        }
        TrustedForwardedHeaderFilter filter =
                new TrustedForwardedHeaderFilter(parsed.set(), properties.isForwardedHeaders());
        FilterRegistrationBean<TrustedForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(FilterOrder.CORE_FORWARDED);
        registration.addUrlPatterns("/*");
        registration.setName("trustedForwardedHeaderFilter");
        return registration;
    }
}
