package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Startup integrity routing: app-store corruption blocks the boot, a tenancy's corruption only quarantines it. */
class AuthzStoreIntegrityTest {

    private record Wiring(AuthzStoreIntegrity integrity, RoleStore roles, SiteStore sites, StorageLayout layout,
                          YamlStore yaml) {}

    private static Wiring wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        RoleStore roles = new RoleStore(layout, yaml);
        SiteStore sites = new SiteStore(layout, yaml);
        return new Wiring(new AuthzStoreIntegrity(roles, sites), roles, sites, layout, yaml);
    }

    /** A role whose inherited role does not exist — {@link RoleGraph#of} rejects it (an invalid graph). */
    private static RoleFile invalidGraph(Realm realm) {
        return new RoleFile(List.of(new Role("a", realm, Set.of(), Set.of("ghost"))));
    }

    private static Site site(String id) {
        return new Site(id, id, null, Locale.ENGLISH, null, null, null);
    }

    @Test
    void validStoresPassWithoutQuarantine(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.roles().saveAppRoles(List.of(new Role("auditor", Realm.APPLICATION, Set.of("VIEW_AUDIT"), Set.of())));
        w.sites().save(site("acme"));
        w.roles().saveSiteRoles("acme", List.of(new Role("editor", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of())));

        w.integrity().validateOnStartup();
        assertTrue(w.integrity().quarantined().isEmpty());
        assertFalse(w.integrity().isQuarantined("acme"));
    }

    @Test
    void anInvalidApplicationStoreAbortsStartup(@TempDir Path tmp) throws IOException {
        Wiring w = wire(tmp);
        // Bypass saveAppRoles (which validates) to plant a tampered file directly.
        w.yaml().write(w.layout().external().resolve("app-roles.yml"), invalidGraph(Realm.APPLICATION));

        assertThrows(IllegalStateException.class, () -> w.integrity().validateOnStartup());
    }

    @Test
    void anInvalidTenancyIsQuarantinedAndOthersSurvive(@TempDir Path tmp) throws IOException {
        Wiring w = wire(tmp);
        w.sites().save(site("acme"));
        w.sites().save(site("beta"));
        // acme's roles.yml is tampered (invalid graph); beta's is well-formed.
        w.yaml().write(w.layout().siteDir("acme").resolve("roles.yml"), invalidGraph(Realm.TENANT));
        w.roles().saveSiteRoles("beta", List.of(new Role("editor", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of())));

        w.integrity().validateOnStartup(); // must NOT throw — degrade, don't crash
        assertEquals(Set.of("acme"), w.integrity().quarantined());
        assertTrue(w.integrity().isQuarantined("acme"));
        assertFalse(w.integrity().isQuarantined("beta"), "a healthy tenancy is unaffected");
    }
}
