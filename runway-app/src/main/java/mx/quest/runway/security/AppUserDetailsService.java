package mx.quest.runway.security;

import java.util.Objects;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * The application realm's {@link UserDetailsService} — it authenticates <b>only</b> against the
 * application user store, so the application security chain can never authenticate a tenant identity.
 * As defense in depth (a hand-edited {@code app-users.yml} is the threat), it also refuses any record
 * that is not in the {@link Realm#APPLICATION} realm: realms never cross.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserStore store;

    public AppUserDetailsService(AppUserStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = store.findByUsername(username)
                .filter(candidate -> candidate.realm() == Realm.APPLICATION)
                .orElseThrow(() -> new UsernameNotFoundException("no application user '" + username + "'"));
        return new RunwayUserDetails(user);
    }
}
