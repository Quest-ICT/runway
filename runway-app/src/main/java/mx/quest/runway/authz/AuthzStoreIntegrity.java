package mx.quest.runway.authz;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates the authorization role stores at startup, <b>fail-closed</b>, and holds the resulting tenancy
 * quarantine set. The role graphs are already validated lazily on first read ({@link RoleGraph#of} rejects a
 * duplicate name, the reserved-chain seal, an unknown inherited role, or a cycle), so a tampered file can
 * never yield a usable graph; this hook makes that check <b>eager</b> and routes the two realms differently:
 *
 * <ul>
 *   <li><b>Application roles</b> ({@code app-roles.yml}) are part of the platform trusted computing base — an
 *       invalid file <b>aborts context refresh</b> (the platform never boots with a broken/tampered app
 *       authorization store), mirroring {@code RealmActivation} for the identity store.</li>
 *   <li><b>Tenancy roles</b> ({@code sites/<id>/roles.yml}) follow <b>degrade-don't-crash</b>: a single
 *       invalid file <b>quarantines that one tenancy</b> (its admin surface is short-circuited by
 *       {@link QuarantinedSiteFilter}) and is logged for the operator, while the platform and every other
 *       tenancy stay up.</li>
 * </ul>
 *
 * <p>Quarantine is a runtime safety state, not a persisted status — repairing the file and restarting clears
 * it (a failed graph load is never cached, so no stale graph lingers). Safe in every mode: absent stores read
 * as empty, which is valid, so first-run WIZARD boot is unaffected.
 */
@Component
public class AuthzStoreIntegrity {

    private static final Logger log = LoggerFactory.getLogger(AuthzStoreIntegrity.class);

    private final RoleStore roles;
    private final SiteStore sites;
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();

    public AuthzStoreIntegrity(RoleStore roles, SiteStore sites) {
        this.roles = roles;
        this.sites = sites;
    }

    /** Validates the role stores; an invalid application store aborts startup, an invalid tenancy is quarantined. */
    @PostConstruct
    public void validateOnStartup() {
        try {
            roles.appRoles(); // RoleGraph.of validates: duplicate, seal, missing ref, cycle
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "application role store (app-roles.yml) is invalid; refusing to start: " + e.getMessage(), e);
        }
        for (Site site : sites.findAll()) {
            try {
                roles.siteRoles(site.id());
            } catch (RuntimeException e) {
                quarantined.add(site.id());
                log.error("Tenancy '{}' has an invalid role store (roles.yml); quarantining its admin surface "
                        + "until repaired and restarted: {}", site.id(), e.getMessage());
            }
        }
        log.info("Authorization role stores validated at startup ({} tenancy/tenancies quarantined).",
                quarantined.size());
    }

    /** Whether {@code siteId}'s role store failed startup validation (its admin surface is held). */
    public boolean isQuarantined(String siteId) {
        return siteId != null && quarantined.contains(siteId);
    }

    /** The ids of tenancies quarantined at startup (a snapshot). */
    public Set<String> quarantined() {
        return Set.copyOf(quarantined);
    }
}
