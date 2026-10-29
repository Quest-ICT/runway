package mx.quest.runway.log;

import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AccessLogFilter} at {@link FilterOrder#CORE_ACCESS_LOG}, just inside the abuse floor, so it logs
 * every public request the floor lets through with its final status, and enables {@link LogProperties}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogProperties.class)
public class AccessLogConfig {

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(SiteLogService siteLog,
                                                                               LogProperties properties) {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(
                new AccessLogFilter(siteLog, properties.isAccessLogUserAgent()));
        registration.setOrder(FilterOrder.CORE_ACCESS_LOG);
        registration.addUrlPatterns("/*");
        registration.setName("accessLogFilter");
        return registration;
    }
}
