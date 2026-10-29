package mx.quest.runway.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.log.AccessLogFilter;
import mx.quest.runway.web.FilterOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/** The protection floor and the access log just inside it sit at their pinned slots; the default guard passes. */
@SpringBootTest
class EdgeProtectionConfigTest {

    @Autowired
    private EdgeGuard edgeGuard;

    @Autowired
    private FilterRegistrationBean<EdgeRejectFilter> edgeReject;

    @Autowired
    private FilterRegistrationBean<AccessLogFilter> accessLog;

    @Test
    void theRejectSeamSitsAtTheProtectionFloor() {
        assertEquals(FilterOrder.CORE_PROTECTION, edgeReject.getOrder());
    }

    @Test
    void theAccessLogSitsJustInsideTheFloor() {
        assertEquals(FilterOrder.CORE_ACCESS_LOG, accessLog.getOrder());
        assertTrue(accessLog.getOrder() > edgeReject.getOrder(), "floor rejections stay out of the access log");
        assertTrue(accessLog.getOrder() < FilterOrder.CORE_SITE_CONTEXT, "outer of every gate that answers alone");
    }

    @Test
    void theKernelDefaultGuardPassesEverything() {
        assertTrue(edgeGuard.check(new EdgeRequest("203.0.113.9", "POST", "/anything")).allowed(),
                "with no policy plugin, the kernel floor degrades to pass-through");
    }

    /**
     * The config actually <b>calls</b> the declaration — the seam is only useful if it is wired.
     *
     * <p>The recorder honouring {@code preRegister} and the guard declaring its outcomes are both tested in
     * isolation, and both would keep passing if the one line joining them were deleted. That failure mode is
     * not hypothetical here: a fix that existed but was never invoked is exactly how the plugin-config reload
     * defect survived review.
     *
     * <p>Built by hand from the config rather than autowired, because the running context's guard is the
     * kernel's pass-through default (no plugin under test) and declares nothing by design.
     */
    @Test
    void theConfigPreRegistersWhateverTheActiveGuardDeclares() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        EdgeGuard declaring = new EdgeGuard() {
            @Override
            public EdgeVerdict check(EdgeRequest request) {
                return EdgeVerdict.reject(429, 5, "test-reason");
            }

            @Override
            public java.util.Set<RejectionOutcome> declaredOutcomes() {
                return java.util.Set.of(new RejectionOutcome(429, "test-reason"));
            }
        };

        new EdgeProtectionConfig().edgeRejectionRecorder(
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        java.util.Map.of("r", registry)).getBeanProvider(
                                io.micrometer.core.instrument.MeterRegistry.class),
                new EdgeProtectionProperties(), declaring);

        assertNotNull(registry.find("runway.edge.rejections")
                        .tag("status", "429").tag("reason", "test-reason").counter(),
                "the guard declared it, so the series must exist before the first rejection");
    }
}
