package mx.quest.runway.reload;

import mx.quest.runway.site.SiteStore;
import mx.quest.runway.web.RunwayProxyProperties;
import mx.quest.runway.web.FilterOrder;
import mx.quest.runway.web.SiteReloadPaths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the loopback-only, token-gated site-reload endpoint at {@link FilterOrder#CORE_SITE_RELOAD}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReloadProperties.class)
public class SiteReloadConfig {

    @Bean
    public SiteReloadService siteReloadService(SiteStore sites, ApplicationEventPublisher events) {
        return new SiteReloadService(sites, events);
    }

    @Bean
    public SiteReloadStartupCheck siteReloadStartupCheck(ReloadProperties reload, RunwayProxyProperties proxy) {
        return new SiteReloadStartupCheck(reload, proxy);
    }

    @Bean
    public FilterRegistrationBean<SiteReloadFilter> siteReloadFilterRegistration(SiteReloadService reloads,
                                                                                ReloadProperties properties) {
        FilterRegistrationBean<SiteReloadFilter> registration =
                new FilterRegistrationBean<>(new SiteReloadFilter(reloads, properties));
        registration.setOrder(FilterOrder.CORE_SITE_RELOAD);
        registration.addUrlPatterns(SiteReloadPaths.PREFIX + "/*");
        registration.setName("siteReloadFilter");
        return registration;
    }
}
