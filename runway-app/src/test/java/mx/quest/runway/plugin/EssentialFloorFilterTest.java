package mx.quest.runway.plugin;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Locale;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** When the floor has failed, a bound tenant request is held (503) but the app realm stays reachable. */
class EssentialFloorFilterTest {

    private static SiteResolver resolver(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        return new SiteResolver(new SiteStore(StorageLayout.from(props), new YamlStore()), TestPlatformSettings.empty());
    }

    private static EssentialFloor floor(boolean failed) {
        EssentialFloor floor = new EssentialFloor();
        floor.set(failed);
        return floor;
    }

    private static MockFilterChain run(SiteResolver resolver, EssentialFloor floor, String boundSite, String uri,
                                       MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        try {
            if (boundSite != null) {
                resolver.bind(new Site(boundSite, boundSite, null, Locale.ENGLISH, null, null, null));
            }
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            request.setRequestURI(uri);
            new EssentialFloorFilter(resolver, floor).doFilter(request, response, chain);
        } finally {
            resolver.clear();
        }
        return chain;
    }

    @Test
    void aBoundTenantRequestIsHeldWhenTheFloorFailed(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = resolver(tmp);
        MockHttpServletResponse publicPage = new MockHttpServletResponse();
        assertNull(run(resolver, floor(true), "acme", "/about", publicPage).getRequest());
        assertEquals(503, publicPage.getStatus());

        MockHttpServletResponse admin = new MockHttpServletResponse();
        assertNull(run(resolver, floor(true), "acme", "/__admin", admin).getRequest(), "tenant admin held too");
        assertEquals(503, admin.getStatus());
    }

    @Test
    void theApplicationRealmStaysReachableEvenWhenFailedOnATenantHost(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = resolver(tmp);
        assertNotNull(run(resolver, floor(true), "acme", "/__app", new MockHttpServletResponse()).getRequest());
        assertNotNull(run(resolver, floor(true), "acme", "/actuator/health", new MockHttpServletResponse()).getRequest());
    }

    @Test
    void loseLookalikeTenantPathsAreHeldNotExempted(@TempDir Path tmp) throws Exception {
        // /__apple and /actuatorX are TENANT pages, not the app realm — the exact-match exemption must hold them.
        SiteResolver resolver = resolver(tmp);
        MockHttpServletResponse apple = new MockHttpServletResponse();
        assertNull(run(resolver, floor(true), "acme", "/__apple", apple).getRequest(), "/__apple is a tenant page");
        assertEquals(503, apple.getStatus());
        MockHttpServletResponse actuatorish = new MockHttpServletResponse();
        assertNull(run(resolver, floor(true), "acme", "/actuatorX", actuatorish).getRequest());
        assertEquals(503, actuatorish.getStatus());
    }

    @Test
    void everythingPassesWhenTheFloorIsHealthyOrNoSiteIsBound(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = resolver(tmp);
        assertNotNull(run(resolver, floor(false), "acme", "/about", new MockHttpServletResponse()).getRequest());
        assertNotNull(run(resolver, floor(true), null, "/about", new MockHttpServletResponse()).getRequest());
    }
}
