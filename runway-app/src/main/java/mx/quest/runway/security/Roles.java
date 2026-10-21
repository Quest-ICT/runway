package mx.quest.runway.security;

/**
 * The reserved, predefined role keys — three per realm — used for the <b>coarse</b> path-authorization
 * band the security chains enforce. They map to {@code ROLE_<name>} Spring authorities. The rich,
 * dynamic role model (custom roles, the role DAG, the permission catalog and {@code mayAccess}
 * short-circuit) lives in the authorization layer ({@code mx.quest.runway.authz}); these names are only the
 * authorities the chains gate on.
 *
 * <p>The realms never cross: application roles are honored only by the application chain, tenant roles
 * only by the tenant chain.
 */
public final class Roles {

    // --- Application realm ---
    /** The pinned, undeletable platform superadmin. */
    public static final String SYSTEM_SUPERADMIN = "SYSTEMSUPERADMIN";
    /** A platform administrator. */
    public static final String SYSTEM_ADMIN = "SYSTEMADMIN";
    /** A platform operator (read-mostly). */
    public static final String SYSTEM_OPERATOR = "SYSTEMOPERATOR";

    // --- Tenant realm ---
    /** A tenant's superadmin (set at tenant creation; immutable from within the tenant). */
    public static final String SUPERADMIN = "SUPERADMIN";
    /** A tenant administrator. */
    public static final String ADMIN = "ADMIN";
    /** A tenant operator. */
    public static final String OPERATOR = "OPERATOR";

    /** The Spring authority prefix Spring Security's {@code hasRole(...)} adds. */
    public static final String AUTHORITY_PREFIX = "ROLE_";

    private Roles() {
    }
}
