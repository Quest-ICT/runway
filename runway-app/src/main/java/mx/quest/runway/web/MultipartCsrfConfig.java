package mx.quest.runway.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.MultipartFilter;

/**
 * Parses {@code multipart/form-data} bodies <b>before</b> Spring Security so the {@code CsrfFilter} can read a
 * CSRF token carried in a multipart form body.
 *
 * <p>Spring Security's chain is pinned at order {@code 100} (after the multi-tenancy filters — see
 * {@code application.yml}). Its {@code CsrfFilter} reads {@code _csrf} via {@code request.getParameter}, which
 * for a multipart request only works once the body has been parsed. Without an early {@link MultipartFilter}
 * the parameters are still unparsed when {@code CsrfFilter} runs, the token is missing, and a legitimate
 * multipart POST (e.g. the backups restore-upload) is rejected {@code 403} at runtime — a failure MockMvc
 * masks. Registering the filter just ahead of security (order {@code 90}, after {@code SiteContext(50)} /
 * {@code Suspended(60)} / {@code Quarantined(65)}) makes the token available in time.
 *
 * <p>It skips a request bound for a plugin route whose multipart body {@code RouteFormCaptureFilter} has already
 * captured raw for the route (SPI 1.0.0): the route parses its own body, and takes its CSRF token in the header.
 */
@Configuration(proxyBeanMethods = false)
public class MultipartCsrfConfig {

    @Bean
    public FilterRegistrationBean<MultipartFilter> multipartFilterBeforeSecurity() {
        FilterRegistrationBean<MultipartFilter> registration = new FilterRegistrationBean<>(new MultipartFilter() {
            @Override
            protected boolean shouldNotFilter(jakarta.servlet.http.HttpServletRequest request) {
                // A plugin route's multipart body was captured raw for the route (RouteFormCaptureFilter); parsing it
                // here would read a stream that is no longer the container's.
                return mx.quest.runway.endpoint.RouteFormCaptureFilter.isCaptured(request);
            }
        });
        registration.setOrder(90); // before Spring Security (100), after the site-context filters
        return registration;
    }
}
