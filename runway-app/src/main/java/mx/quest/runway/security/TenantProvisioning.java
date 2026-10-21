package mx.quest.runway.security;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import org.springframework.stereotype.Service;

/**
 * Provisions tenant-superadmin identities — the application-realm operation that gives a tenancy its
 * always-present superadmin (set at tenancy creation). A provisioned
 * master is a {@link Realm#TENANT} identity in the {@link TenantMasterStore} (global, application-owned),
 * holding {@link Roles#SUPERADMIN} and assigned to the tenancy. Because it lives in the master store and
 * not the tenancy's own {@code users.yml}, the tenancy can fully operate it but can never delete or
 * demote it from within the tenant realm.
 *
 * <p>This is the kernel operation; wiring it into the site-creation flow and the application-realm UI
 * (which collect the master's username/password) is built with those surfaces.
 */
@Service
public class TenantProvisioning {

    private final TenantMasterStore masters;

    public TenantProvisioning(TenantMasterStore masters) {
        this.masters = Objects.requireNonNull(masters, "masters");
    }

    /**
     * Provisions a tenant superadmin assigned to {@code siteId}.
     *
     * @param principalId  the new master's stable principal id
     * @param username     its login name
     * @param passwordHash the Argon2id PHC hash (hashed by the caller)
     * @param siteId       the tenancy it superadmins
     * @param displayName  a human label
     * @param recoveryEmail an out-of-band recovery address
     * @return the provisioned identity
     */
    public User provisionMaster(String principalId, String username, String passwordHash, String siteId,
                                String displayName, String recoveryEmail) {
        User master = new User(principalId, username, passwordHash, displayName, recoveryEmail, true, true,
                Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of(siteId));
        masters.save(master);
        return master;
    }
}
