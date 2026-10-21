package mx.quest.runway.health;

import mx.quest.runway.authz.AuthzStoreIntegrity;
import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.plugin.EssentialFloor;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.web.FilterOrder;
import mx.quest.runway.web.SiteHealthPaths;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the per-site health probe ({@code /__health}) into the TENANT band at
 * {@link FilterOrder#CORE_SITE_HEALTH} — after the site is bound from the Host, before every gate that could
 * short-circuit it. See {@link SiteHealthFilter} for why that slot is the whole design.
 *
 * <p>The registration is scoped to the exact path, so the filter is not even entered for ordinary traffic.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HealthProperties.class)
@ConditionalOnProperty(prefix = "runway.health", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SiteHealthConfig {

    @Bean
    public SiteContentReadiness siteContentReadiness(StorageLayout layout, PageRouting routing,
                                                     HealthProperties properties) {
        return new SiteContentReadiness(layout, routing, properties);
    }

    @Bean
    public SiteHealthService siteHealthService(EssentialFloor essentialFloor, BandwidthService bandwidth,
                                               AuthzStoreIntegrity authzIntegrity, SiteContentReadiness content) {
        return new SiteHealthService(essentialFloor, bandwidth, authzIntegrity, content);
    }

    @Bean
    public FilterRegistrationBean<SiteHealthFilter> siteHealthFilterRegistration(
            SiteResolver siteResolver, SiteHealthService health, HealthProperties properties) {
        FilterRegistrationBean<SiteHealthFilter> registration =
                new FilterRegistrationBean<>(new SiteHealthFilter(siteResolver, health, properties));
        registration.setOrder(FilterOrder.CORE_SITE_HEALTH);
        registration.addUrlPatterns(SiteHealthPaths.PATH);
        registration.setName("siteHealthFilter");
        return registration;
    }
}
