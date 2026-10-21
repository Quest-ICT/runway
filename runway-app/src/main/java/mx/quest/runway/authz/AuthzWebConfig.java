package mx.quest.runway.authz;

import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the authorization servlet filter in the TENANT band of the kernel-wide ladder
 * ({@link FilterOrder}). {@link QuarantinedSiteFilter} sits at {@link FilterOrder#CORE_QUARANTINED_SITE} —
 * inner of the suspended-site short-circuit (the site is already bound) and outer of the security chain
 * ({@link FilterOrder#CORE_SECURITY}), so it can 503 a quarantined tenancy's admin surface before
 * authorization is attempted.
 */
@Configuration(proxyBeanMethods = false)
public class AuthzWebConfig {

    @Bean
    public FilterRegistrationBean<QuarantinedSiteFilter> quarantinedSiteFilterRegistration(
            SiteResolver resolver, AuthzStoreIntegrity integrity) {
        FilterRegistrationBean<QuarantinedSiteFilter> registration =
                new FilterRegistrationBean<>(new QuarantinedSiteFilter(resolver, integrity));
        registration.setOrder(FilterOrder.CORE_QUARANTINED_SITE);
        registration.addUrlPatterns("/*");
        registration.setName("quarantinedSiteFilter");
        return registration;
    }
}
