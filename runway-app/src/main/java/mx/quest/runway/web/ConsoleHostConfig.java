package mx.quest.runway.web;

import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.site.SiteResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link ConsoleHostFilter} at {@link FilterOrder#CORE_CONSOLE_HOST}. The registration exists in every
 * mode (so the filter order can be checked in any boot); in setup (wizard) mode — no
 * {@code runway.security.app-master-id} yet — the filter is inert, so the first-run wizard is never affected.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleHostProperties.class)
public class ConsoleHostConfig {

    @Bean
    public FilterRegistrationBean<ConsoleHostFilter> consoleHostFilterRegistration(
            SiteResolver resolver, PlatformSettingsStore platform, ConsoleHostProperties properties,
            Environment environment) {
        String pin = environment.getProperty("runway.security.app-master-id");
        boolean active = pin != null && !pin.isBlank();
        FilterRegistrationBean<ConsoleHostFilter> registration = new FilterRegistrationBean<>(
                new ConsoleHostFilter(resolver, platform, properties.getHosts(), active));
        registration.setOrder(FilterOrder.CORE_CONSOLE_HOST);
        registration.addUrlPatterns("/*");
        registration.setName("consoleHostFilter");
        return registration;
    }
}
