package mx.quest.runway.site;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SuspendedSiteFilterTest {

    private static final Locale EN = Locale.ENGLISH;

    private static SiteResolver emptyResolver(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        return new SiteResolver(new SiteStore(StorageLayout.from(props), new YamlStore()), TestPlatformSettings.empty());
    }

    private static Site site(String id, SiteStatus status) {
        return new Site(id, id, List.of(id + ".test"), EN, List.of(EN), null, status);
    }

    @Test
    void suspendedSiteIsShortCircuitedWith503(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = emptyResolver(tmp);
        resolver.bind(site("acme", SiteStatus.SUSPENDED));
        boolean[] chainCalled = {false};
        FilterChain chain = (req, resp) -> chainCalled[0] = true;

        MockHttpServletResponse response = new MockHttpServletResponse();
        new SuspendedSiteFilter(resolver).doFilter(new MockHttpServletRequest(), response, chain);

        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.getStatus());
        assertFalse(chainCalled[0], "a suspended site must not reach the rest of the chain");
        resolver.clear();
    }

    @Test
    void activeSitePassesThrough(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = emptyResolver(tmp);
        resolver.bind(site("acme", SiteStatus.ACTIVE));
        boolean[] chainCalled = {false};
        new SuspendedSiteFilter(resolver).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, resp) -> chainCalled[0] = true);
        assertTrue(chainCalled[0]);
        resolver.clear();
    }

    @Test
    void noBoundSitePassesThrough(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = emptyResolver(tmp);
        boolean[] chainCalled = {false};
        new SuspendedSiteFilter(resolver).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, resp) -> chainCalled[0] = true);
        assertTrue(chainCalled[0]);
    }
}
