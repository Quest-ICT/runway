package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.Environment;

/** The forwarded-header gate is the outermost filter, and the container's native handling is off. */
@SpringBootTest
class ForwardedHeaderConfigTest {

    @Autowired
    private FilterRegistrationBean<TrustedForwardedHeaderFilter> registration;

    @Autowired
    private Environment environment;

    @Test
    void theGateIsRegisteredStrictlyOutermost() {
        assertEquals(FilterOrder.CORE_FORWARDED, registration.getOrder());
        assertEquals(Integer.MIN_VALUE, registration.getOrder(), "CORE_FORWARDED must be the most-outer order");
    }

    @Test
    void theContainerNativeForwardedHandlingIsOff() {
        // The gated filter is the SOLE handler; the container must not also (ungatedly) process X-Forwarded-*.
        String strategy = environment.getProperty("server.forward-headers-strategy");
        assertTrue("none".equalsIgnoreCase(strategy),
                "server.forward-headers-strategy must resolve to 'none', was: " + strategy);
    }
}
