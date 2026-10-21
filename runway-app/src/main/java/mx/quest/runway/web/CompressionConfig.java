package mx.quest.runway.web;

import mx.quest.runway.platform.HostSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the single {@link CompressionFilter} at {@link FilterOrder#CORE_COMPRESSION} and binds
 * {@link RunwayCompressionProperties}. The filter is also exposed as a plain bean so the operator About page can
 * read {@link CompressionFilter#brotliNativeAvailable()}; the {@link FilterRegistrationBean} referencing it
 * suppresses Spring Boot's default filter auto-registration, so it is wired exactly once, at the pinned order.
 *
 * <p>Jetty's own gzip handler is left off ({@code server.compression.enabled: false} in {@code application.yml})
 * so this filter is the one and only compression decision — brotli and gzip in one place, no double-encoding.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunwayCompressionProperties.class)
public class CompressionConfig {

    @Bean
    public CompressionFilter compressionFilter(RunwayCompressionProperties properties,
                                               PlatformSettingsStore platform, HostSettings host) {
        return new CompressionFilter(properties, platform, host);
    }

    @Bean
    public FilterRegistrationBean<CompressionFilter> compressionFilterRegistration(CompressionFilter filter) {
        FilterRegistrationBean<CompressionFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(FilterOrder.CORE_COMPRESSION);
        registration.addUrlPatterns("/*");
        registration.setName("compressionFilter");
        return registration;
    }
}
