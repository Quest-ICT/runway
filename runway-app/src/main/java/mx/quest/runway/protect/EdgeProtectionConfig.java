package mx.quest.runway.protect;

import io.micrometer.core.instrument.MeterRegistry;
import mx.quest.runway.web.FilterOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the EDGE_INGRESS protection floor: the kernel's permissive default {@link EdgeGuard} and the
 * {@link EdgeRejectFilter} registered at {@link FilterOrder#CORE_PROTECTION}, one slot inside the
 * forwarded-header gate. The essential protection plugin supplies its own {@code @Primary}
 * {@link EdgeGuard}, which wins by-type injection over this always-present default (a robust override that
 * does not depend on bean-definition ordering, unlike {@code @ConditionalOnMissingBean} in a user config).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EdgeProtectionProperties.class)
public class EdgeProtectionConfig {

    /** The kernel floor default: pass everything. The essential plugin's {@code @Primary} guard wins. */
    @Bean
    public EdgeGuard permissiveEdgeGuard() {
        return new PermissiveEdgeGuard();
    }

    /**
     * Makes edge rejections observable. {@code MeterRegistry} is taken through an {@link ObjectProvider} so the
     * protection floor never depends on metrics being present — the floor must come up in any configuration,
     * and losing the counter is preferable to failing to start the thing that rejects abuse.
     */
    @Bean
    public EdgeRejectionRecorder edgeRejectionRecorder(ObjectProvider<MeterRegistry> registry,
                                                       EdgeProtectionProperties properties,
                                                       EdgeGuard guard) {
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry.getIfAvailable(),
                properties.isLogRejections(), properties.getLogRejectionWindowSeconds());
        // Ask the ACTIVE guard (the plugin's @Primary one where installed) what it can answer with, and give
        // each of those series a zero at startup. Core deliberately does not know the vocabulary — see
        // EdgeGuard#declaredOutcomes for why an absent series is not the same as a zero one.
        recorder.preRegister(guard.declaredOutcomes());
        return recorder;
    }

    @Bean
    public FilterRegistrationBean<EdgeRejectFilter> edgeRejectFilterRegistration(EdgeGuard guard,
                                                                                 EdgeRejectionRecorder recorder) {
        FilterRegistrationBean<EdgeRejectFilter> registration =
                new FilterRegistrationBean<>(new EdgeRejectFilter(guard, recorder));
        registration.setOrder(FilterOrder.CORE_PROTECTION);
        registration.addUrlPatterns("/*");
        registration.setName("edgeRejectFilter");
        return registration;
    }
}
