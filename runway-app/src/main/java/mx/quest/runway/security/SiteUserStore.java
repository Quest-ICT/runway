package mx.quest.runway.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.stereotype.Component;

/**
 * A tenancy's own sub-user store — {@code external/sites/<id>/users.yml}, the per-tenancy
 * {@code ADMIN}/{@code OPERATOR} accounts created by that tenancy's superadmin. Living inside the
 * site folder, these users ride the tenant backup and restore with it. The path is resolved through
 * {@link StorageLayout#siteDir(String)}, so the {@code siteId} is traversal-safe and can never reach
 * another tenancy's file.
 *
 * <p>This store does <b>not</b> hold the tenant superadmin: that is a global identity in the
 * {@link TenantMasterStore}, application-managed and merely assigned to the tenancy. A tenancy can
 * therefore never delete or demote its own superadmin from within the tenant realm — it writes only
 * this file, never {@code site-users.yml}.
 */
@Component
public class SiteUserStore {

    private static final String FILE = "users.yml";

    private final StorageLayout layout;
    private final UserStore users;

    public SiteUserStore(StorageLayout layout, UserStore users) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.users = Objects.requireNonNull(users, "users");
    }

    /** The sub-user of {@code siteId} with this login name (case-insensitive), or empty. */
    public Optional<User> findByUsername(String siteId, String username) {
        return users.findByUsername(file(siteId), username);
    }

    /** The sub-user of {@code siteId} with this stable principal id, or empty. */
    public Optional<User> findByPrincipalId(String siteId, String principalId) {
        return users.findByPrincipalId(file(siteId), principalId);
    }

    /** Every sub-user of {@code siteId}. */
    public List<User> list(String siteId) {
        return users.list(file(siteId));
    }

    /**
     * Upserts a sub-user into {@code siteId}'s store.
     *
     * <p>A site user and the tenant superadmins assigned to the site sign in on the same form, so a login name
     * is refused when the save introduces it ({@link UserStore#claimsNewName}: a new user, or a rename) and it is
     * already held — case-insensitively, trimmed — by another user of this site ({@link UserStore#save}) or by a
     * tenant superadmin assigned to this site (directly or through {@code "*"}). Without the second check a holder
     * of {@code MANAGE_USERS} could create a user named like the superadmin and sign in under that name. The
     * cross-file check is not atomic with an application-realm assignment made at the same instant; sign-in
     * resolves such a clash in favour of the superadmin ({@link SiteUserDetailsService}).
     *
     * @throws UsernameTakenException if the save would introduce a login name already in use on this site
     */
    public void save(String siteId, User user) {
        Objects.requireNonNull(user, "user");
        Path file = file(siteId);
        if (UserStore.claimsNewName(users.findByPrincipalId(file, user.principalId()), user)) {
            boolean superadminHoldsIt = users.list(TenantMasterStore.file(layout)).stream()
                    .anyMatch(master -> master.assignedTo(siteId) && master.username().equalsIgnoreCase(user.username()));
            if (superadminHoldsIt) {
                throw new UsernameTakenException(user.username());
            }
        }
        users.save(file, user);
    }

    /** Removes a sub-user from {@code siteId}'s store. */
    public boolean delete(String siteId, String principalId) {
        return users.delete(file(siteId), principalId);
    }

    private Path file(String siteId) {
        return file(layout, siteId);
    }

    /** {@code siteId}'s {@code users.yml} under {@code layout} (traversal-safe: {@code siteDir} validates the id). */
    static Path file(StorageLayout layout, String siteId) {
        return layout.siteDir(siteId).resolve(FILE);
    }
}
