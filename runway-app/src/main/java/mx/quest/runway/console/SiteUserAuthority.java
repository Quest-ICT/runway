package mx.quest.runway.console;

import java.util.Objects;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permissions;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.springframework.stereotype.Component;

/**
 * Who may manage <b>which</b> of a site's users on the site console's Users screen ({@code /__admin/users}).
 *
 * <h2>The verbs: two grantable permissions</h2>
 * {@link Permissions#MANAGE_USERS} opens the screen and its create / edit / suspend / resume / reset-password
 * actions; {@link Permissions#DELETE_USERS} the delete action (and the screen, read-only, on its own). Both are
 * checked through {@link AuthorizationService#mayAccess}, so the site's superadmin and its administrators
 * ({@code ADMIN}, the wildcard tier) keep passing as they always did, and anybody else needs the permission granted
 * to one of their roles or to them directly.
 *
 * <h2>The targets: bounded by the actor's own authority</h2>
 * A permission says what an actor may <em>do</em>; it must not become a way to reach accounts more powerful than
 * the actor (bounded authority; "a baseline permission is never a back-door"). Resetting the password
 * of an account that holds more than you is taking that account over. So a holder who is <b>not</b> an
 * administrator of the site may act only on a user who
 * <ul>
 *   <li>is not an administrator of the site (the {@code ADMIN} wildcard tier) and not its superadmin, and</li>
 *   <li>holds no permission the actor does not hold themselves (target's effective set ⊆ actor's).</li>
 * </ul>
 * The site's superadmin and administrators act on every user of the site, exactly as before. The checks that were
 * already there — tier changes need grant authority over both tiers, nobody suspends or re-tiers themselves, the
 * superadmin is out of this store's reach — still apply on top, in {@link ConsoleUserService}.
 *
 * <p>Assigning <b>roles</b> is not part of {@code MANAGE_USERS}: the access tier of a user a non-administrator
 * creates is {@code OPERATOR} at most (they cannot grant {@code ADMIN}), and layering custom roles at creation —
 * a role assignment — stays with the site's administrators, like the permissions matrix.
 */
@Component
public class SiteUserAuthority {

    private final AuthorizationService authorization;

    public SiteUserAuthority(AuthorizationService authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    /** Whether {@code actor} holds {@code MANAGE_USERS} in {@code tenancyId} (administrators always do). */
    public boolean mayManage(RunwayUserDetails actor, String tenancyId) {
        return actor != null && authorization.mayAccess(actor, Permissions.MANAGE_USERS, tenancyId);
    }

    /** Whether {@code actor} holds {@code DELETE_USERS} in {@code tenancyId} (administrators always do). */
    public boolean mayDelete(RunwayUserDetails actor, String tenancyId) {
        return actor != null && authorization.mayAccess(actor, Permissions.DELETE_USERS, tenancyId);
    }

    /** Whether {@code actor} is the site's superadmin or one of its administrators (the wildcard tier). */
    public boolean isSiteAdmin(RunwayUserDetails actor, String tenancyId) {
        return actor != null && (authorization.isMaster(actor, tenancyId) || authorization.isWildcard(actor, tenancyId));
    }

    /**
     * Whether {@code actor} may act on {@code target} (edit, suspend, resume, reset its password, delete it) —
     * the target bound described on the class. It does not check the verb's permission; callers check that first.
     */
    public boolean mayActOn(RunwayUserDetails actor, String tenancyId, User target) {
        if (actor == null || target == null || target.realm() != Realm.TENANT) {
            return false;
        }
        if (isSiteAdmin(actor, tenancyId)) {
            return true;
        }
        RunwayUserDetails subject = new RunwayUserDetails(target);
        if (target.master() || authorization.isWildcard(subject, tenancyId)
                || authorization.isMaster(subject, tenancyId)) {
            return false; // never an administrator or the superadmin of the site
        }
        Set<String> mine = authorization.effectivePermissionKeys(actor, tenancyId);
        return mine.containsAll(authorization.effectivePermissionKeys(subject, tenancyId));
    }

    /**
     * Refuses unless {@code actor} may act on {@code target}.
     *
     * @throws ConsoleUserService.UserEditException with code {@code beyondAuthority}
     */
    public void requireMayActOn(RunwayUserDetails actor, String tenancyId, User target) {
        if (!mayActOn(actor, tenancyId, target)) {
            throw new ConsoleUserService.UserEditException("beyondAuthority",
                    "this account holds more than you do — only the site's administrators can change it");
        }
    }
}
