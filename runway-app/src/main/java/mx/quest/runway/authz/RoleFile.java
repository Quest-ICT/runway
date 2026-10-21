package mx.quest.runway.authz;

import java.util.List;

/**
 * The on-disk shape of a role store — a {@code roles:} list. A single top-level record (not a bare list)
 * keeps the YAML forward-compatible.
 *
 * @param roles the roles in this scope (copied, unmodifiable; {@code null} → empty)
 */
public record RoleFile(List<Role> roles) {

    public RoleFile {
        roles = (roles == null) ? List.of() : List.copyOf(roles);
    }

    /** An empty store (an absent file). */
    public static RoleFile empty() {
        return new RoleFile(List.of());
    }
}
