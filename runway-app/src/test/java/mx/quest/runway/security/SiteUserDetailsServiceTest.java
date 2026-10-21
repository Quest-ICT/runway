package mx.quest.runway.security;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The load-bearing tenant-isolation point: a username authenticates only against the bound tenancy. */
class SiteUserDetailsServiceTest {

    private record Wiring(SiteResolver resolver, SiteUserStore siteUsers, TenantMasterStore masters,
                          SiteUserDetailsService service) {}

    private static Wiring wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("beta.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        SiteResolver resolver = new SiteResolver(sites, TestPlatformSettings.empty());
        UserStore users = new UserStore(yaml);
        return new Wiring(resolver, new SiteUserStore(layout, users), new TenantMasterStore(layout, users),
                new SiteUserDetailsService(resolver, new SiteUserStore(layout, users), new TenantMasterStore(layout, users)));
    }

    private static User tenantUser(String id, String username, String site) {
        return new User(id, username, "$argon2id$h", username + "@" + site, "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of());
    }

    private void bind(Wiring w, String siteId) {
        w.resolver().clear();
        w.resolver().bind(w.resolver().resolve(siteId.equals("acme") ? "acme.test" : "beta.test").orElseThrow());
    }

    @Test
    void aUsernameResolvesToTheBoundTenancyOnly(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        // Both tenancies have an "admin" — distinct accounts.
        w.siteUsers().save("acme", tenantUser("acme-admin", "admin", "acme"));
        w.siteUsers().save("beta", tenantUser("beta-admin", "admin", "beta"));

        bind(w, "acme");
        assertEquals("acme-admin", ((RunwayUserDetails) w.service().loadUserByUsername("admin")).principalId());
        bind(w, "beta");
        assertEquals("beta-admin", ((RunwayUserDetails) w.service().loadUserByUsername("admin")).principalId(),
                "site B's admin must never resolve while site A could be bound");
    }

    @Test
    void withNoSiteBoundTenantAuthenticationIsRefused(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.siteUsers().save("acme", tenantUser("acme-admin", "admin", "acme"));
        w.resolver().clear();
        assertThrows(UsernameNotFoundException.class, () -> w.service().loadUserByUsername("admin"));
    }

    @Test
    void aTenantMasterResolvesOnlyForItsAssignedTenancies(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        // A global tenant-master assigned to acme only.
        w.masters().save(new User("boss", "boss", "$argon2id$h", "Boss", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));

        bind(w, "acme");
        UserDetails loaded = w.service().loadUserByUsername("boss");
        assertEquals("boss", ((RunwayUserDetails) loaded).principalId());

        bind(w, "beta");
        assertThrows(UsernameNotFoundException.class, () -> w.service().loadUserByUsername("boss"),
                "the master is not assigned to beta");
    }

    @Test
    void aNonTenantRecordIsNeverAuthenticatedByTheTenantChain(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.siteUsers().save("acme", new User("x", "intruder", "$h", "", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_ADMIN), List.of()));
        bind(w, "acme");
        assertThrows(UsernameNotFoundException.class, () -> w.service().loadUserByUsername("intruder"));
    }
}
