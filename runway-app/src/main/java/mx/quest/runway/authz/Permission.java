package mx.quest.runway.authz;

import java.util.Objects;
import mx.quest.runway.spi.model.Realm;

/**
 * A realm-tagged authorization permission — the unit a role or user is granted. Permissions are
 * <b>programmatic and declared</b> (by the core, and contributed by plugins), never
 * user-created; each belongs to exactly one {@link Realm} and is meaningless outside it (the core never
 * offers a tenant permission to an application surface, or vice versa).
 *
 * <p>The {@link #key()} is the stable, persisted identifier (e.g. {@code EDIT_PAGES}); it is the
 * forward-compatible contract with stored grants, so the catalog can evolve and the permission can move
 * or be renamed in code without rewriting data. Two permissions are equal iff their key <em>and</em>
 * realm match.
 *
 * <p><b>Host-internal since SPI 0.4.0.</b> It used to live in {@code mx.quest.runway.spi.model}, but no plugin ever
 * constructed or received one: plugins <em>declare</em> permissions in {@code plugin.yml} and <em>check</em> them by
 * key string ({@code @authz.may('<id>|<perm>')}). Keeping an unused record in the frozen contract would have
 * frozen a host data shape for nothing, so it moved here.
 *
 * @param key   the stable permission key (never {@code null}/blank)
 * @param realm the realm this permission belongs to (never {@code null})
 */
public record Permission(String key, Realm realm) {

    public Permission {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("permission key is blank");
        }
        Objects.requireNonNull(realm, "realm");
    }
}
