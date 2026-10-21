package mx.quest.runway.security;

import java.util.Optional;

/**
 * The scope a login form guard resolves its policy against — the pairing of the security realm with, for the
 * tenant realm, the bound site id. It is the kernel's answer to "whose protection config governs THIS login
 * POST": the application realm's operator login is governed app-wide (no site), while a tenant realm's
 * {@code /login} is governed by the currently-bound site's own per-site protection config.
 *
 * <p>Constructed by the {@link LoginProtectionFilter} — the app-realm shell always passes {@link #application()}
 * (no site), the tenant-realm shell passes {@link #forSite(String)} with the site resolved for the request
 * ({@code SiteResolver.current()}). The {@link LoginFormGuard} implementation uses {@link #realm()} and
 * {@link #siteId()} to pick the app store vs the per-site store — never anything the client supplies.
 *
 * @param realm  the realm this login belongs to (never {@code null})
 * @param siteId the bound site id for a {@link Realm#TENANT} login, or {@code null} for {@link Realm#APPLICATION}
 *               (and for a tenant login whose site could not be resolved — the guard then falls back to defaults)
 */
public record ProtectionScope(Realm realm, String siteId) {

    /** The two never-crossing security realms a login form guard can be scoped to. */
    public enum Realm {
        /** The operator login ({@code /app/login}); governed by the app-level protection config, no site. */
        APPLICATION,
        /** A site's tenant login ({@code /login}); governed by the bound site's own per-site protection config. */
        TENANT
    }

    /** The application-realm scope (operator login) — governed app-wide, no site. */
    public static ProtectionScope application() {
        return new ProtectionScope(Realm.APPLICATION, null);
    }

    /**
     * A tenant-realm scope bound to {@code siteId}. A {@code null}/blank id yields a site-less tenant scope — the
     * guard then applies its defaults (fail-safe: an unresolvable site never blocks the login, it degrades to the
     * permissive baseline), never another site's config.
     */
    public static ProtectionScope forSite(String siteId) {
        return new ProtectionScope(Realm.TENANT, siteId != null && !siteId.isBlank() ? siteId : null);
    }

    /** The bound site id if one was resolved (present only for a resolved {@link Realm#TENANT} scope). */
    public Optional<String> resolvedSiteId() {
        return Optional.ofNullable(siteId);
    }
}
