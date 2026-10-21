package mx.quest.runway.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.stereotype.Component;

/**
 * The application realm's user store — {@code external/app-users.yml}, holding the platform operators
 * including the pinned, undeletable application superadmin. Wraps the realm-agnostic {@link UserStore}
 * with the application realm's two invariants:
 *
 * <ul>
 *   <li><b>The master-pin guard (on mutation).</b> The application superadmin is matched by the stable
 *       {@link RunwaySecurityProperties#getAppMasterId() pinned principal id} (never the renameable
 *       username). It can never be deleted, disabled, demoted (role/master-flag/realm changed), or
 *       renamed from within the realm — only its password and profile (display name, recovery email)
 *       may change. Mutations violating this throw.</li>
 *   <li><b>Fail-closed load validation.</b> {@link #validate()} enforces that the on-disk store agrees
 *       with the pin — exactly one master record, with the pinned id, enabled, holding
 *       {@code SYSTEMSUPERADMIN}. A tampered store (a second master, a pin mismatch, a disabled or
 *       demoted master) is rejected so the application realm refuses to start rather than booting open;
 *       before provisioning (no pin) the store must hold no master.</li>
 * </ul>
 *
 * <p>This file is part of the trusted computing base: it lives under the external root, never under a
 * web-served or tenant-writable path.
 */
@Component
public class AppUserStore {

    private static final String FILE = "app-users.yml";

    private final StorageLayout layout;
    private final UserStore users;
    private final RunwaySecurityProperties properties;

    public AppUserStore(StorageLayout layout, UserStore users, RunwaySecurityProperties properties) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.users = Objects.requireNonNull(users, "users");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** The application user with this login name (case-insensitive), or empty. */
    public Optional<User> findByUsername(String username) {
        return users.findByUsername(file(), username);
    }

    /** The application user with this stable principal id, or empty. */
    public Optional<User> findByPrincipalId(String principalId) {
        return users.findByPrincipalId(file(), principalId);
    }

    /** Every application user, in file order. */
    public List<User> list() {
        return users.list(file());
    }

    /**
     * Upserts {@code user}. If it targets the pinned master, only a password/profile change is allowed;
     * a structural change (disable, demote, rename, un-pin, realm) is rejected.
     *
     * @throws IllegalStateException if the change would weaken the pinned application superadmin
     */
    public void save(User user) {
        Objects.requireNonNull(user, "user");
        if (isPinned(user.principalId())) {
            findByPrincipalId(user.principalId()).ifPresent(current -> guardMasterMutation(current, user));
        }
        users.save(file(), user);
    }

    /**
     * Removes the user with {@code principalId}.
     *
     * @return whether a user was removed
     * @throws IllegalStateException if it targets the pinned application superadmin
     */
    public boolean delete(String principalId) {
        if (isPinned(principalId)) {
            throw new IllegalStateException("the application superadmin cannot be deleted");
        }
        return users.delete(file(), principalId);
    }

    /**
     * Fail-closed load check: the store must agree with the configured pin. Call at application-realm
     * activation; a failure must block the realm from starting (never boot open).
     *
     * @throws IllegalStateException if the store is invalid for the configured pin
     */
    public void validate() {
        List<User> all = list();
        requireUniqueUsernames(all);

        List<User> masters = all.stream().filter(User::master).toList();
        String pin = properties.getAppMasterId();
        if (pin == null || pin.isBlank()) {
            if (!masters.isEmpty()) {
                throw new IllegalStateException(
                        "app-users.yml declares a master but no application-master pin is configured");
            }
            // Unprovisioned (WIZARD): no master — and no stray superadmin authority either.
            requireNoStraySuperadmin(all, null);
            return;
        }
        if (masters.size() != 1) {
            throw new IllegalStateException(
                    "app-users.yml must declare exactly one master; found " + masters.size());
        }
        User master = masters.get(0);
        if (!master.principalId().equals(pin)) {
            throw new IllegalStateException("the master record does not match the configured application-master pin");
        }
        if (!master.enabled()) {
            throw new IllegalStateException("the application superadmin must be enabled");
        }
        if (!master.hasRole(Roles.SYSTEM_SUPERADMIN)) {
            throw new IllegalStateException("the application superadmin must hold the " + Roles.SYSTEM_SUPERADMIN + " role");
        }
        if (master.realm() != Realm.APPLICATION) {
            throw new IllegalStateException("the application superadmin must belong to the APPLICATION realm");
        }
        // The platform-superadmin role belongs ONLY to the pinned master — a non-master record holding it
        // would be a live, undeletable-guard-free superadmin.
        requireNoStraySuperadmin(all, master.principalId());
    }

    private static void requireNoStraySuperadmin(List<User> all, String masterPrincipalId) {
        for (User user : all) {
            if (user.hasRole(Roles.SYSTEM_SUPERADMIN) && !user.principalId().equals(masterPrincipalId)) {
                throw new IllegalStateException("only the pinned master may hold " + Roles.SYSTEM_SUPERADMIN
                        + "; found it on '" + user.principalId() + "'");
            }
        }
    }

    private static void requireUniqueUsernames(List<User> all) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (User user : all) {
            if (!seen.add(user.username().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalStateException("duplicate username in app-users.yml: '" + user.username() + "'");
            }
        }
    }

    private boolean isPinned(String principalId) {
        String pin = properties.getAppMasterId();
        return pin != null && !pin.isBlank() && pin.equals(principalId);
    }

    /** Allows only password / display-name / recovery-email changes to the pinned master. */
    private static void guardMasterMutation(User current, User updated) {
        if (!updated.enabled()) {
            throw new IllegalStateException("the application superadmin cannot be disabled");
        }
        if (!updated.master()) {
            throw new IllegalStateException("the application superadmin pin cannot be removed");
        }
        if (!current.username().equalsIgnoreCase(updated.username())) {
            throw new IllegalStateException("the application superadmin username cannot be changed");
        }
        if (!current.roles().equals(updated.roles())) {
            throw new IllegalStateException("the application superadmin roles cannot be changed");
        }
        if (current.realm() != updated.realm()) {
            throw new IllegalStateException("the application superadmin realm cannot be changed");
        }
    }

    private Path file() {
        return layout.external().resolve(FILE);
    }
}
