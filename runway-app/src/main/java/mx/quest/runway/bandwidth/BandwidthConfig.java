package mx.quest.runway.bandwidth;

import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link BandwidthFilter} at {@link FilterOrder#CORE_BANDWIDTH} (outside compression, inside the
 * forwarded-header gate) and enables {@link BandwidthProperties}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BandwidthProperties.class)
public class BandwidthConfig {

    @Bean
    public FilterRegistrationBean<BandwidthFilter> bandwidthFilterRegistration(SiteResolver siteResolver,
                                                                               BandwidthService bandwidth) {
        FilterRegistrationBean<BandwidthFilter> registration =
                new FilterRegistrationBean<>(new BandwidthFilter(siteResolver, bandwidth));
        registration.setOrder(FilterOrder.CORE_BANDWIDTH);
        registration.addUrlPatterns("/*");
        registration.setName("bandwidthFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<BandwidthLimitFilter> bandwidthLimitFilterRegistration(SiteResolver siteResolver,
                                                                                         BandwidthService bandwidth) {
        FilterRegistrationBean<BandwidthLimitFilter> registration =
                new FilterRegistrationBean<>(new BandwidthLimitFilter(siteResolver, bandwidth));
        registration.setOrder(FilterOrder.CORE_BANDWIDTH_LIMIT);
        registration.addUrlPatterns("/*");
        registration.setName("bandwidthLimitFilter");
        return registration;
    }
}
