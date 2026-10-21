package mx.quest.runway.log;

import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AccessLogFilter} as the innermost filter ({@link FilterOrder#CORE_ACCESS_LOG}), so its
 * post-phase observes the final response status of every public request, and enables {@link LogProperties}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogProperties.class)
public class AccessLogConfig {

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(SiteResolver siteResolver,
                                                                               SiteLogService siteLog,
                                                                               LogProperties properties) {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(
                new AccessLogFilter(siteResolver, siteLog, properties.isAccessLogUserAgent()));
        registration.setOrder(FilterOrder.CORE_ACCESS_LOG);
        registration.addUrlPatterns("/*");
        registration.setName("accessLogFilter");
        return registration;
    }
}
