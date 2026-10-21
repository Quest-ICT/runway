package mx.quest.runway.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Adapts a kernel {@link User} to Spring Security's {@link UserDetails}, exposing the user's coarse
 * {@link Roles} as {@code ROLE_<name>} authorities and carrying the realm + stable principal id so
 * downstream code can reason about identity without re-reading the store. The credential it returns is
 * the opaque Argon2id PHC string, which the chain's encoder verifies.
 *
 * <p>The account is "enabled" exactly when the stored user is; the lock/expiry flags are always true
 * here (login throttling is an edge filter, not a per-account state).
 *
 * <p><b>{@link Serializable}</b> because it is the principal inside the {@code Authentication} that Spring
 * Security stores in the {@code HttpSession}: when {@code runway.cluster.enabled=true} the session (and this
 * object with it) is serialized into the Hazelcast sessions {@code IMap} so any node can read it. Its two
 * fields are both serializable — the {@link User} record (made {@link Serializable} for the same reason) and
 * the derived authorities list, whose elements ({@code SimpleGrantedAuthority}) and container ({@code
 * Stream.toList}'s immutable list) are already serializable.
 */
public final class RunwayUserDetails implements UserDetails, Serializable {

    private static final long serialVersionUID = 1L;

    private final User user;
    private final List<GrantedAuthority> authorities;

    public RunwayUserDetails(User user) {
        this.user = user;
        this.authorities = user.roles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(Roles.AUTHORITY_PREFIX + role))
                .toList();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.passwordHash();
    }

    @Override
    public String getUsername() {
        return user.username();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.enabled();
    }

    /** The stable principal id (the identity that survives a username change). */
    public String principalId() {
        return user.principalId();
    }

    /** The realm this identity belongs to. */
    public Realm realm() {
        return user.realm();
    }

    /** Whether this is the pinned realm master (the undeletable superadmin). */
    public boolean master() {
        return user.master();
    }

    /** Whether this identity is assigned to {@code siteId} (a tenant-master's {@code sites}/{@code "*"}). */
    public boolean assignedTo(String siteId) {
        return user.assignedTo(siteId);
    }

    /**
     * The identity record this principal was built from — the snapshot taken at login (or at the last
     * {@link AccountStateFilter} refresh). Compared against the store on every request so an edit, suspension
     * or deletion takes effect on the session's next request.
     */
    public User user() {
        return user;
    }

    /** The role names carried on the identity record (the authorization layer adds per-scope grants on top). */
    public java.util.Set<String> roles() {
        return user.roles();
    }
}

