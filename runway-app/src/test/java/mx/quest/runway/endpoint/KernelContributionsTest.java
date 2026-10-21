package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.quality.PageQualityCheckRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the no-two-class rule: the kernel's own contributions are collected by the same by-type registries a
 * plugin's would be. A booted context must already contain a built-in endpoint (the favicon route) and the
 * built-in title check — no plugin needed. ({@code /robots.txt} is now left to a plugin.)
 */
@SpringBootTest
class KernelContributionsTest {

    @Autowired
    private EndpointContributorRegistry endpoints;

    @Autowired
    private PageQualityCheckRegistry quality;

    @Test
    void theBuiltinFaviconEndpointIsCollected() {
        assertTrue(endpoints.match("GET", "/favicon.ico").isPresent(),
                "the built-in favicon endpoint must be collected like any plugin endpoint");
    }

    @Test
    void theBuiltinQualityCheckIsCollected() {
        assertFalse(quality.isEmpty(), "the core title check must be collected like any plugin check");
    }
}
