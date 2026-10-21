package mx.quest.runway.authz;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jakarta.servlet.ServletException;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The quarantine filter: hold a quarantined tenancy's admin surface (503), pass its public pages and the app realm. */
class QuarantinedSiteFilterTest {

    private record Wiring(QuarantinedSiteFilter filter, SiteResolver siteResolver) {}

    private static Wiring wire(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        RoleStore roles = new RoleStore(layout, yaml);
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
        sites.save(new Site("beta", "Beta", null, Locale.ENGLISH, null, null, null));
        // acme's role store is tampered (inherits a non-existent role) → quarantined at startup; beta is fine.
        yaml.write(layout.siteDir("acme").resolve("roles.yml"),
                new RoleFile(List.of(new Role("a", Realm.TENANT, Set.of(), Set.of("ghost")))));
        roles.saveSiteRoles("beta", List.of(new Role("editor", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of())));

        AuthzStoreIntegrity integrity = new AuthzStoreIntegrity(roles, sites);
        integrity.validateOnStartup();
        SiteResolver siteResolver = new SiteResolver(sites, TestPlatformSettings.empty());
        return new Wiring(new QuarantinedSiteFilter(siteResolver, integrity), siteResolver);
    }

    private static MockFilterChain run(Wiring w, String boundSiteId, String uri,
                                       MockHttpServletResponse response) throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        try {
            if (boundSiteId != null) {
                w.siteResolver().bind(new Site(boundSiteId, boundSiteId, null, Locale.ENGLISH, null, null, null));
            }
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            request.setRequestURI(uri);
            w.filter().doFilter(request, response, chain);
        } finally {
            w.siteResolver().clear();
        }
        return chain;
    }

    @Test
    void aQuarantinedTenancysAdminSurfaceIsHeld(@TempDir Path tmp) throws Exception {
        Wiring w = wire(tmp);
        MockHttpServletResponse home = new MockHttpServletResponse();
        assertNull(run(w, "acme", "/__admin", home).getRequest(), "the chain must not be reached");
        assertEquals(503, home.getStatus());

        MockHttpServletResponse sub = new MockHttpServletResponse();
        assertNull(run(w, "acme", "/__admin/users", sub).getRequest(), "a sub-path of the admin surface is held too");
        assertEquals(503, sub.getStatus());
    }

    @Test
    void aQuarantinedTenancysPublicPagesStillServe(@TempDir Path tmp) throws Exception {
        Wiring w = wire(tmp);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(w, "acme", "/about", response);
        assertNotNull(chain.getRequest(), "public pages do not consult authorization, so they pass");
    }

    @Test
    void aHealthyTenancyAdminSurfacePasses(@TempDir Path tmp) throws Exception {
        Wiring w = wire(tmp);
        MockFilterChain chain = run(w, "beta", "/__admin", new MockHttpServletResponse());
        assertNotNull(chain.getRequest());
    }

    @Test
    void noBoundSitePasses(@TempDir Path tmp) throws Exception {
        Wiring w = wire(tmp);
        MockFilterChain chain = run(w, null, "/__admin", new MockHttpServletResponse());
        assertNotNull(chain.getRequest());
    }
}
