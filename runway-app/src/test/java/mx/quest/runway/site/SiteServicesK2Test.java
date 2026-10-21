package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.RequestContextImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code Sites.find} (unsafe id refused, unknown id empty, suspended included) and {@code RequestContext.site}
 * (the bound tenancy on a public page or the tenant console; empty on the application realm, where the resolver
 * would otherwise have bound the catch-all default site, and off a request).
 */
class SiteServicesK2Test {

    @TempDir
    Path external;
    private SiteStore store;
    private SiteResolver resolver;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        store = new SiteStore(StorageLayout.from(props), new YamlStore());
        store.save(new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        store.save(new Site("paused", "Paused", List.of("paused.test"), Locale.ENGLISH, List.of(), null,
                SiteStatus.SUSPENDED));
        resolver = new SiteResolver(store, TestPlatformSettings.empty());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        resolver.clear();
    }

    @Test
    void sitesFindsAViewByIdIncludingSuspendedSites() {
        SitesImpl sites = new SitesImpl(store);
        SiteView acme = sites.find("acme").orElseThrow();
        assertEquals("Acme", acme.displayName());
        assertEquals("https://acme.test", acme.baseUrl());
        assertTrue(sites.find("paused").isPresent(), "a suspended site still exists");
        assertEquals(Optional.empty(), sites.find("nobody"), "a well-formed unknown id is not an error");
        for (String unsafe : new String[] {"..", "a/b", "", "x|y", null}) {
            assertThrows(IllegalArgumentException.class, () -> sites.find(unsafe), String.valueOf(unsafe));
        }
    }

    private void onRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        resolver.bind(store.find("acme").orElseThrow()); // what SiteContextFilter does for every request
    }

    @Test
    void requestContextIsTheBoundSiteOnPublicAndTenantConsoleRequests() {
        RequestContextImpl context = new RequestContextImpl(resolver);
        onRequest("/about");
        assertEquals("acme", context.site().orElseThrow().id());
        onRequest("/__admin/blog");
        assertEquals("acme", context.site().orElseThrow().id());
    }

    @Test
    void requestContextIsEmptyOnTheApplicationRealmEvenWithACatchAllSiteBound() {
        RequestContextImpl context = new RequestContextImpl(resolver);
        for (String uri : List.of("/__app", "/__app/plugins", "/app/login", "/actuator/health")) {
            onRequest(uri);
            assertEquals(Optional.empty(), context.site(), uri);
        }
        onRequest("/__apple");
        assertTrue(context.site().isPresent(), "a tenant page named like the realm is not the realm");
    }

    @Test
    void requestContextIsEmptyOffARequestAndNeverThrows() {
        RequestContextImpl context = new RequestContextImpl(resolver);
        resolver.bind(store.find("acme").orElseThrow()); // a stray binding with no request
        assertEquals(Optional.empty(), context.site());
    }
}
