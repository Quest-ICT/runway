package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** The application UserDetailsService: role authorities, enabled state, and realm rejection. */
class AppUserDetailsServiceTest {

    private static AppUserDetailsService wire(Path tmp, User... seed) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        AppUserStore store = new AppUserStore(StorageLayout.from(props), new UserStore(new YamlStore()),
                new RunwaySecurityProperties());
        for (User u : seed) {
            store.save(u);
        }
        return new AppUserDetailsService(store);
    }

    @Test
    void loadsAnApplicationUserWithRoleAuthorities(@TempDir Path tmp) {
        AppUserDetailsService service = wire(tmp, new User("a-1", "alice", "$h", "Alice", "", true, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_ADMIN), List.of()));

        UserDetails details = service.loadUserByUsername("alice");
        assertTrue(details.isEnabled());
        assertTrue(details.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_" + Roles.SYSTEM_ADMIN)));
    }

    @Test
    void reflectsTheDisabledState(@TempDir Path tmp) {
        AppUserDetailsService service = wire(tmp, new User("a-1", "alice", "$h", "Alice", "", false, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_ADMIN), List.of()));
        assertFalse(service.loadUserByUsername("alice").isEnabled());
    }

    @Test
    void missingUserThrows(@TempDir Path tmp) {
        AppUserDetailsService service = wire(tmp);
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("nobody"));
    }

    @Test
    void aTenantRealmRecordIsNeverAuthenticatedByTheApplicationChain(@TempDir Path tmp) {
        // Defense in depth: a hand-edited app-users.yml with a TENANT record must not authenticate here.
        AppUserDetailsService service = wire(tmp, new User("t-1", "intruder", "$h", "", "", true, false,
                Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of("acme")));
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("intruder"));
    }
}
