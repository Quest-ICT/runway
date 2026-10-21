package mx.quest.runway.security;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The cross-tenant defense: an /__admin decision must require the principal be bound to the current site. */
class TenantSiteAuthorizationManagerTest {

    private record Wiring(SiteResolver resolver, SiteUserStore siteUsers, TenantSiteAuthorizationManager manager) {}

    private static Wiring wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("beta.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        SiteResolver resolver = new SiteResolver(sites, TestPlatformSettings.empty());
        SiteUserStore siteUsers = new SiteUserStore(layout, new UserStore(yaml));
        return new Wiring(resolver, siteUsers, new TenantSiteAuthorizationManager(resolver, siteUsers));
    }

    private static void bind(Wiring w, String host) {
        w.resolver().clear();
        w.resolver().bind(w.resolver().resolve(host).orElseThrow());
    }

    private static Authentication authFor(User user) {
        RunwayUserDetails details = new RunwayUserDetails(user);
        return new UsernamePasswordAuthenticationToken(details, "x", details.getAuthorities());
    }

    private static boolean granted(Wiring w, Authentication auth) {
        return w.manager().authorize(() -> auth, new RequestAuthorizationContext(new MockHttpServletRequest("GET", "/__admin"))).isGranted();
    }

    private static User subUser(String id, String username) {
        return new User(id, username, "$h", "", "", true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of());
    }

    @Test
    void aSubUserIsAuthorizedOnItsOwnSiteButDeniedOnAnother(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.siteUsers().save("acme", subUser("acme-admin", "admin"));
        Authentication acmeAdmin = authFor(subUser("acme-admin", "admin"));

        bind(w, "acme.test");
        assertTrue(granted(w, acmeAdmin), "acme's admin on acme is authorized");
        bind(w, "beta.test");
        assertFalse(granted(w, acmeAdmin), "the same session replayed against beta must be denied");
    }

    @Test
    void aTenantMasterIsAuthorizedOnlyForAssignedSites(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        Authentication boss = authFor(new User("boss", "boss", "$h", "", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));

        bind(w, "acme.test");
        assertTrue(granted(w, boss));
        bind(w, "beta.test");
        assertFalse(granted(w, boss), "the master is not assigned to beta");
    }

    @Test
    void noSiteBoundIsDenied(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.siteUsers().save("acme", subUser("acme-admin", "admin"));
        w.resolver().clear();
        assertFalse(granted(w, authFor(subUser("acme-admin", "admin"))));
    }

    @Test
    void anApplicationRolePrincipalIsDenied(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        bind(w, "acme.test");
        Authentication appUser = authFor(new User("a", "a", "$h", "", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_ADMIN), List.of()));
        assertFalse(granted(w, appUser), "an application role is not a tenant role");
    }

    @Test
    void anAnonymousAuthenticationIsDenied(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        bind(w, "acme.test");
        Authentication anon = new AnonymousAuthenticationToken("k", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertFalse(granted(w, anon));
    }
}
