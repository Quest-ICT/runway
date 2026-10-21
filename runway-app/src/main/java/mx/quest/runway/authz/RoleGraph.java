package mx.quest.runway.authz;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, validated view of one realm/scope's custom-role DAG, with the effective-permission
 * resolver. Built (and validated) by {@link #of(Collection)}; a graph object is only ever a <em>valid</em>
 * graph — construction <b>fails closed</b> on any violation rather than silently dropping an edge:
 *
 * <ul>
 *   <li>a duplicate role name;</li>
 *   <li>the <b>seal</b>: a role named a {@linkplain ReservedRoles reserved key}, or inheriting one;</li>
 *   <li>a role inheriting a role that does not exist; or</li>
 *   <li>a cycle.</li>
 * </ul>
 *
 * <p>{@link #effectivePermissionKeys(String)} returns a role's own permission keys plus the transitive
 * downward union of every role it inherits (parent accumulates descendants). The per-role closure is the
 * only thing cached. Thread-safe after construction.
 */
public final class RoleGraph {

    private final Map<String, Role> byName;
    private final Map<String, Set<String>> closureCache = new HashMap<>();

    private RoleGraph(Map<String, Role> byName) {
        this.byName = byName;
    }

    /**
     * Builds and validates the graph for {@code roles}.
     *
     * @throws IllegalStateException on a duplicate name, a seal violation, an unknown inherited role, or a cycle
     */
    public static RoleGraph of(Collection<Role> roles) {
        Map<String, Role> byName = new LinkedHashMap<>();
        for (Role role : roles) {
            if (ReservedRoles.isReserved(role.name())) {
                throw new IllegalStateException("a custom role may not bear the reserved key '" + role.name() + "'");
            }
            if (byName.putIfAbsent(role.name(), role) != null) {
                throw new IllegalStateException("duplicate role name: " + role.name());
            }
        }
        for (Role role : byName.values()) {
            for (String inherited : role.inherits()) {
                if (ReservedRoles.isReserved(inherited)) {
                    throw new IllegalStateException("role '" + role.name() + "' may not inherit the reserved key '" + inherited + "'");
                }
                if (!byName.containsKey(inherited)) {
                    throw new IllegalStateException("role '" + role.name() + "' inherits unknown role '" + inherited + "'");
                }
            }
        }
        RoleGraph graph = new RoleGraph(byName);
        graph.requireAcyclic();
        return graph;
    }

    /** A role's effective permission keys: its own plus the transitive downward union of what it inherits. */
    public Set<String> effectivePermissionKeys(String roleName) {
        if (!byName.containsKey(roleName)) {
            return Set.of(); // an unknown role grants nothing
        }
        return closureCache.computeIfAbsent(roleName, name -> {
            Set<String> keys = new HashSet<>();
            accumulate(name, keys, new HashSet<>());
            return Set.copyOf(keys);
        });
    }

    /**
     * The role-name closure of {@code roleName}: itself plus every role it transitively inherits. A name not in
     * the custom graph (e.g. a predefined seed role like {@code OPERATOR}) closes to <b>just itself</b>, so it
     * still participates in the plugin-permission-default fold ({@link AuthorizationService}). The dual of
     * {@link #effectivePermissionKeys} at the role level.
     */
    public Set<String> effectiveRoleNames(String roleName) {
        if (!byName.containsKey(roleName)) {
            return Set.of(roleName);
        }
        Set<String> names = new HashSet<>();
        accumulateNames(roleName, names);
        return Set.copyOf(names);
    }

    private void accumulateNames(String roleName, Set<String> names) {
        if (!names.add(roleName)) {
            return;
        }
        Role role = byName.get(roleName);
        if (role != null) {
            for (String inherited : role.inherits()) {
                accumulateNames(inherited, names);
            }
        }
    }

    /**
     * The permission keys stored on {@code roleName} itself (its {@code permissions} in the roles file), without
     * what it inherits; empty for a name not in the graph. The authorization fold applies the role's revokes to
     * these per role, so a key typed into the file can be revoked from the console like any other.
     */
    public Set<String> ownPermissionKeys(String roleName) {
        Role role = byName.get(roleName);
        return role == null ? Set.of() : Set.copyOf(role.permissions());
    }

    /** Whether a role with this name exists in the graph. */
    public boolean hasRole(String name) {
        return byName.containsKey(name);
    }

    /** Every role name in the graph. */
    public Set<String> roleNames() {
        return Set.copyOf(byName.keySet());
    }

    private void accumulate(String roleName, Set<String> keys, Set<String> seen) {
        if (!seen.add(roleName)) {
            return;
        }
        Role role = byName.get(roleName);
        if (role == null) {
            return;
        }
        keys.addAll(role.permissions());
        for (String inherited : role.inherits()) {
            accumulate(inherited, keys, seen);
        }
    }

    private void requireAcyclic() {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        for (String name : byName.keySet()) {
            if (hasCycle(name, visited, onStack)) {
                throw new IllegalStateException("role hierarchy has a cycle through '" + name + "'");
            }
        }
    }

    private boolean hasCycle(String name, Set<String> visited, Set<String> onStack) {
        if (onStack.contains(name)) {
            return true;
        }
        if (!visited.add(name)) {
            return false;
        }
        onStack.add(name);
        for (String inherited : byName.get(name).inherits()) {
            if (hasCycle(inherited, visited, onStack)) {
                return true;
            }
        }
        onStack.remove(name);
        return false;
    }
}
