package mx.quest.runway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the observability additions on top of Actuator's built-in metrics: the per-site request timer
 * ({@link SiteRequestMetricsFilter}, at {@link FilterOrder#CORE_SITE_METRICS}) and the optional Prometheus
 * scrape-token filter ({@link PrometheusScrapeAuthFilter}, added to the app security chain by
 * {@code AppSecurityConfig}). Route-level timers are already provided for free by Actuator's
 * {@code http.server.requests}; this only adds the tenant dimension and the token.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsConfig {

    @Bean
    public FilterRegistrationBean<SiteRequestMetricsFilter> siteRequestMetricsFilterRegistration(
            SiteResolver siteResolver, MeterRegistry registry) {
        FilterRegistrationBean<SiteRequestMetricsFilter> registration =
                new FilterRegistrationBean<>(new SiteRequestMetricsFilter(siteResolver, registry));
        registration.setOrder(FilterOrder.CORE_SITE_METRICS);
        registration.addUrlPatterns("/*");
        registration.setName("siteRequestMetricsFilter");
        return registration;
    }

    /**
     * The scrape-token filter as a plain bean (not self-registered), so {@code AppSecurityConfig} can place it
     * inside the application security chain — ahead of authorization — rather than in the outer servlet chain.
     */
    @Bean
    public PrometheusScrapeAuthFilter prometheusScrapeAuthFilter(MetricsProperties properties) {
        return new PrometheusScrapeAuthFilter(properties);
    }

    /**
     * Drops Micrometer's stock {@code disk.free}/{@code disk.total} gauges, which we replace with the label-free
     * {@code runway.storage.*} gauges in {@link KernelMetrics}.
     *
     * <p>The stock binder tags its series with the path it measures and defaults to {@code "."} — the JVM working
     * directory — and the launcher cds to {@code RUNWAY_HOME}, the <b>versioned</b> release dir. The label was
     * therefore {@code path="/opt/runway-<version>/."}: every upgrade minted a new series and abandoned the old
     * one, and any dashboard, alert or recording rule pinning {@code path=} silently stopped matching — an empty
     * result rather than an error. A monitored deployment had accumulated ten such series across four weeks of
     * releases. It also measured the wrong filesystem: what an operator needs is free
     * space where DATA is written, not where the jar happens to live.
     *
     * <p>Done as a filter rather than as configuration because Boot rejects an empty
     * {@code management.metrics.system.diskspace.paths} list, and excluding the whole
     * {@code SystemMetricsAutoConfiguration} would also drop uptime, CPU and file-descriptor metrics that are
     * worth keeping. A denied meter is never registered, so the binder does no per-scrape work either.
     */
    @Bean
    public MeterFilter suppressPathTaggedDiskMetrics() {
        return MeterFilter.deny(id -> id.getName().startsWith("disk."));
    }
}
