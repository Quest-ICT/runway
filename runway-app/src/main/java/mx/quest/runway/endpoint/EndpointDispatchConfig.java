package mx.quest.runway.endpoint;

import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.FilterOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link EndpointDispatchFilter} as the terminal application handler
 * ({@link FilterOrder#CORE_ENDPOINT_DISPATCH}) — inner of the plugin APPLICATION window (a plugin filter can
 * still wrap it) and after the security chain, so a matched route is served before it would 404 at the page
 * controller.
 */
@Configuration(proxyBeanMethods = false)
public class EndpointDispatchConfig {

    @Bean
    public FilterRegistrationBean<EndpointDispatchFilter> endpointDispatchFilterRegistration(
            EndpointContributorRegistry registry, SiteResolver siteResolver, ContributionGuard guard,
            @Value("${runway.endpoints.max-body-bytes:10485760}") long maxBodyBytes,
            @Value("${runway.endpoints.max-form-bytes:262144}") long maxFormBytes) {
        FilterRegistrationBean<EndpointDispatchFilter> registration = new FilterRegistrationBean<>(
                new EndpointDispatchFilter(registry, siteResolver, maxBodyBytes, maxFormBytes, guard));
        registration.setOrder(FilterOrder.CORE_ENDPOINT_DISPATCH);
        registration.addUrlPatterns("/*");
        registration.setName("endpointDispatchFilter");
        return registration;
    }

    /**
     * Boot's {@code FormContentFilter} with plugin routes excluded ({@link RouteAwareFormContentFilter}) — under Boot's
     * own bean name, so the auto-configured one stands down.
     */
    @Bean
    public RouteAwareFormContentFilter formContentFilter(ObjectProvider<EndpointContributorRegistry> registry,
                                                         PreAuthBodyLimit preAuthBodyLimit) {
        return new RouteAwareFormContentFilter(registry, preAuthBodyLimit.cap());
    }

    /**
     * The pre-authentication body budget ({@link PreAuthBodyLimit}): {@code runway.endpoints.max-form-bytes}, or
     * {@code max-body-bytes} if smaller. Each realm's security chain adds its {@link PreAuthBodyLimit#filter()}.
     */
    @Bean
    public PreAuthBodyLimit preAuthBodyLimit(
            @Value("${runway.endpoints.max-body-bytes:10485760}") long maxBodyBytes,
            @Value("${runway.endpoints.max-form-bytes:262144}") long maxFormBytes) {
        return PreAuthBodyLimit.of(maxBodyBytes, maxFormBytes);
    }

    /**
     * The route form-body capture ({@link FilterOrder#CORE_ROUTE_FORM_CAPTURE}), ahead of the security chain so its
     * CSRF check cannot consume a plugin route's form body. Because it reads before authentication, it has its own
     * small cap, {@code runway.endpoints.max-form-bytes} (default 256 KiB; never above {@code max-body-bytes}).
     */
    @Bean
    public FilterRegistrationBean<RouteFormCaptureFilter> routeFormCaptureFilterRegistration(
            EndpointContributorRegistry registry, SiteResolver siteResolver,
            @Value("${runway.endpoints.max-body-bytes:10485760}") long maxBodyBytes,
            @Value("${runway.endpoints.max-form-bytes:262144}") long maxFormBytes) {
        FilterRegistrationBean<RouteFormCaptureFilter> registration = new FilterRegistrationBean<>(
                new RouteFormCaptureFilter(registry, siteResolver, maxBodyBytes, maxFormBytes));
        registration.setOrder(FilterOrder.CORE_ROUTE_FORM_CAPTURE);
        registration.addUrlPatterns("/*");
        registration.setName("routeFormCaptureFilter");
        return registration;
    }
}
