package mx.quest.runway.site;

import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the multi-tenancy servlet filters in the TENANT band of the kernel-wide ladder
 * ({@link FilterOrder}): {@link FilterOrder#CORE_SITE_CONTEXT} resolves and binds the site first, then
 * {@link FilterOrder#CORE_SUSPENDED_SITE} short-circuits a suspended tenant, and
 * {@link FilterOrder#CORE_MAINTENANCE} short-circuits a tenant that put its own public site into maintenance
 * (while exempting the tenant admin realm) — all resolve-then-check, so the site is bound before it is read.
 * All stay outer of the security chain ({@link FilterOrder#CORE_SECURITY}) so every realm/tenant-scoped
 * decision downstream sees a bound {@code SiteResolver.current()}.
 */
@Configuration(proxyBeanMethods = false)
public class SiteWebConfig {

    @Bean
    public FilterRegistrationBean<SiteContextFilter> siteContextFilterRegistration(SiteResolver resolver) {
        FilterRegistrationBean<SiteContextFilter> registration =
                new FilterRegistrationBean<>(new SiteContextFilter(resolver));
        registration.setOrder(FilterOrder.CORE_SITE_CONTEXT);
        registration.addUrlPatterns("/*");
        registration.setName("siteContextFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<SuspendedSiteFilter> suspendedSiteFilterRegistration(SiteResolver resolver) {
        FilterRegistrationBean<SuspendedSiteFilter> registration =
                new FilterRegistrationBean<>(new SuspendedSiteFilter(resolver));
        registration.setOrder(FilterOrder.CORE_SUSPENDED_SITE);
        registration.addUrlPatterns("/*");
        registration.setName("suspendedSiteFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<MaintenanceSiteFilter> maintenanceSiteFilterRegistration(SiteResolver resolver) {
        FilterRegistrationBean<MaintenanceSiteFilter> registration =
                new FilterRegistrationBean<>(new MaintenanceSiteFilter(resolver));
        registration.setOrder(FilterOrder.CORE_MAINTENANCE);
        registration.addUrlPatterns("/*");
        registration.setName("maintenanceSiteFilter");
        return registration;
    }
}
