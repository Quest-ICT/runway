package mx.quest.runway.authz;

import java.util.Objects;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;

/**
 * A custom role: a named bundle of granted permission keys plus a position in the role DAG. A role
 * {@link #inherits} the roles directly <b>beneath</b> it, so its effective permissions are its own plus
 * the transitive downward union of everything it inherits (computed by {@link RoleGraph}).
 *
 * <p>Roles live in the per-realm role files ({@code app-roles.yml}, {@code sites/<id>/roles.yml}); the
 * <b>reserved chain</b> (master &gt; wildcard) is code-defined and never stored here — a custom role may
 * neither be named a {@linkplain ReservedRoles reserved key} nor inherit one (the seal, enforced by
 * {@link RoleGraph}). Permissions and inherited names are stored as <b>keys/names</b> (the
 * forward-compatible contract with the data); an undeclared permission key simply grants nothing.
 *
 * @param name        the role's unique name within its realm/scope (never blank)
 * @param realm       the realm this role belongs to (never {@code null})
 * @param permissions the permission keys granted directly by this role (copied, unmodifiable)
 * @param inherits    the names of the roles directly beneath this one (copied, unmodifiable)
 */
public record Role(String name, Realm realm, Set<String> permissions, Set<String> inherits) {

    public Role {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("role name is blank");
        }
        name = name.trim();
        Objects.requireNonNull(realm, "realm");
        permissions = (permissions == null) ? Set.of() : Set.copyOf(permissions);
        inherits = (inherits == null) ? Set.of() : Set.copyOf(inherits);
    }
}
