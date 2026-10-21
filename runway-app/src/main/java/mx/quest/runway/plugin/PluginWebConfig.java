package mx.quest.runway.plugin;

import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link EssentialFloorFilter} in the TENANT band at {@link FilterOrder#CORE_ESSENTIAL_FLOOR}
 * (after the site is bound, before the security chain), so a missing/broken essential security-floor plugin
 * holds tenant hosts with a {@code 503} while the application realm stays reachable.
 */
@Configuration(proxyBeanMethods = false)
public class PluginWebConfig {

    @Bean
    public FilterRegistrationBean<EssentialFloorFilter> essentialFloorFilterRegistration(
            SiteResolver resolver, EssentialFloor floor) {
        FilterRegistrationBean<EssentialFloorFilter> registration =
                new FilterRegistrationBean<>(new EssentialFloorFilter(resolver, floor));
        registration.setOrder(FilterOrder.CORE_ESSENTIAL_FLOOR);
        registration.addUrlPatterns("/*");
        registration.setName("essentialFloorFilter");
        return registration;
    }
}
