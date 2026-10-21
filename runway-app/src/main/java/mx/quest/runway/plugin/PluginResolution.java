package mx.quest.runway.plugin;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the discovered (enabled, valid) plugins into a <b>loadable</b> set and a <b>blocked</b> map of
 * operator-readable reasons. The <b>same</b> resolver feeds both the loader and the admin UI's
 * "Blocked" preview, so the badge can never disagree with what actually loaded. The passes, in this locked
 * order:
 *
 * <ol>
 *   <li><b>Refusals that need no other plugin</b>, the descriptor-only ones first (so a plugin failing several
 *       rules shows the one the operator acts on): the <b>mandatory floor</b> (from SPI 1.0.0, a plugin with a
 *       {@code mainClass} must declare {@code requiresSpi} at least {@value #MINIMUM_FLOOR_TEXT} — see
 *       {@link #MINIMUM_FLOOR}); an SPI floor above the running SPI; an SPI MAJOR ceiling (a plugin built for an
 *       older MAJOR line); then a pre-blocked id (a quarantine marker, a linkage-scanner or surface-check refusal);
 *       then a {@code requires:} naming an essential plugin.</li>
 *   <li><b>Conflicts</b>, against the full enabled set: a plugin declaring {@code conflicts: [x]} is
 *       blocked when {@code x} is present. <b>Mutual</b> declarations block <b>both</b> (the operator disables
 *       one); a one-sided declaration steps only the declarer aside. Order-independent (full-set, not
 *       pairwise).</li>
 *   <li><b>Requirements to a fixpoint</b>: loop until stable, blocking any plugin whose {@code requires:} target
 *       is missing/disabled, version-mismatched, or itself blocked — so blocking <b>cascades transitively</b>,
 *       and a {@code requires:} cycle (unsatisfiable) blocks the whole cycle. Deterministic (discovery order),
 *       so the reason is reproducible.</li>
 * </ol>
 *
 * <p>Disabled plugins are already excluded by discovery, so a requirement on a disabled plugin reads as the
 * conflated "not installed or disabled" — matching the operator-facing reason.
 */
public final class PluginResolution {

    /** The text of {@link #MINIMUM_FLOOR}. */
    public static final String MINIMUM_FLOOR_TEXT = "0.4.0";

    /**
     * The lowest {@code requiresSpi} floor a plugin with code may declare on an SPI 1.x kernel (the mandatory
     * floor). {@code 0.4.0} is where the SPI service tier began — the injected services that replaced
     * the host-internal classes plugins used to link — so a floor of {@code 0.4.0}, {@code 0.5.0} or {@code 0.6.0}
     * names a plugin built for (a prefix of) the 1.0 contract, and is accepted by every 1.x kernel; the 0.x→1.0 step
     * froze that tier, it did not change it. A missing floor, or one below {@code 0.4.0}, marks a plugin built for the
     * pre-1.0 contract — host classes, host events, {@code runway-app} as a dependency — which no 1.x kernel loads.
     * Library-only plugins (no {@code mainClass}) carry no code the host calls and are exempt. The essential plugin
     * is not exempt: it declares {@code >=1.0.0} (it ships with the kernel), so the rule never trips the fail-closed
     * floor for the plugin the kernel itself installs.
     */
    public static final PluginVersion MINIMUM_FLOOR = PluginVersion.parse(MINIMUM_FLOOR_TEXT);

    /** The outcome: the plugins that may load, and why each blocked one did not. */
    public record Result(List<LoadedPlugin> loadable, Map<String, String> blocked) {
        public Result {
            loadable = List.copyOf(loadable);
            blocked = Map.copyOf(blocked);
        }
    }

    private PluginResolution() {
    }

    /** Resolves {@code candidates} (the enabled, valid discovered plugins, in discovery order). */
    public static Result resolve(List<LoadedPlugin> candidates) {
        return resolve(candidates, runningSpi());
    }

    /**
     * As {@link #resolve(List)}, with {@code preBlocked} plugins refused up front — the quarantine markers and the
     * linkage scanner's hard refusals, which are decided before resolution but must cascade through
     * {@code requires:} like any other block. The loader and the plugins screen both call this form, so the
     * screen's "Blocked" badge cannot disagree with what the next boot loads.
     *
     * @param preBlocked plugin id → operator-readable reason (never {@code null}; may be empty)
     */
    public static Result resolve(List<LoadedPlugin> candidates, Map<String, String> preBlocked) {
        return resolve(candidates, runningSpi(), preBlocked);
    }

    /** The running kernel's SPI level. */
    static PluginVersion runningSpi() {
        return PluginVersion.parse(mx.quest.runway.spi.RunwaySpi.runtimeVersion());
    }

    /**
     * As {@link #resolve(List)}, against an explicit SPI level.
     *
     * @param spiVersion the kernel's {@code RunwaySpi.runtimeVersion()} — a parameter so the floor check is testable
     *                   against both sides of a boundary without a second kernel build
     */
    public static Result resolve(List<LoadedPlugin> candidates, PluginVersion spiVersion) {
        return resolve(candidates, spiVersion, Map.of());
    }

    /** The full form: an explicit SPI level and a set of plugins refused before resolution starts. */
    public static Result resolve(List<LoadedPlugin> candidates, PluginVersion spiVersion,
                                 Map<String, String> preBlocked) {
        Map<String, LoadedPlugin> byId = new LinkedHashMap<>();
        for (LoadedPlugin plugin : candidates) {
            byId.putIfAbsent(plugin.id(), plugin);
        }
        Map<String, String> blocked = new LinkedHashMap<>();

        // Pass 0a — the mandatory floor (from SPI 1.0.0). Decided from the descriptor alone and checked before the
        // scanner's refusals, so a pre-1.0 plugin (which usually also links host classes) is blocked with the reason
        // that tells the operator what to do: install the plugin's release for this Runway.
        if (spiVersion != null && spiVersion.major() >= 1) {
            for (LoadedPlugin plugin : candidates) {
                String reason = preContractReason(plugin, spiVersion);
                if (reason != null) {
                    blocked.putIfAbsent(plugin.id(), reason);
                }
            }
        }

        // Pass 0 — the kernel SPI floor. FIRST, because a plugin built against a newer SPI does not merely
        // misbehave: it loads, registers, and then throws NoSuchMethodError the first time the affected path
        // runs — possibly on a public page request, long after startup, naming a method the operator has never
        // heard of. Refusing at startup turns that into a line they can read and act on, and it says WHICH SIDE
        // to move, which the crash never does. Cascades like any other block: a plugin requiring a
        // floor-blocked one is blocked too (pass 2b).
        for (LoadedPlugin plugin : candidates) {
            PluginVersion floor = plugin.descriptor().spiFloor();
            if (floor != null && (spiVersion == null || spiVersion.compareTo(floor) < 0)) {
                blocked.putIfAbsent(plugin.id(), "needs Runway SPI " + floor + " or later; this Runway "
                        + "provides " + spiVersion + ". " + advice(plugin, "Upgrade Runway, or install an earlier "
                        + "version of this plugin."));
            } else if (floor != null && spiVersion.major() > Math.max(floor.major(), 1)) {
                // The MAJOR ceiling: the SPI is additive within a MAJOR line only, so a plugin built
                // for 1.x must not be wired into a 2.x kernel, where a removed or changed member would surface as a
                // NoSuchMethodError at request time. A 0.x floor counts as 1.x-compatible: the pre-1.0 line and
                // 1.x are one contract, frozen at 1.0.0.
                blocked.putIfAbsent(plugin.id(), "was built for Runway SPI " + Math.max(floor.major(), 1)
                        + ".x; this Runway provides " + spiVersion + ", a newer major version that is not "
                        + "compatible. " + advice(plugin, "Install a version of this plugin built for SPI "
                        + spiVersion.major() + ".x."));
            }
        }

        // Refusals decided outside the resolver (quarantine markers, the linkage scanner, the surface check).
        for (LoadedPlugin plugin : candidates) {
            String reason = preBlocked.get(plugin.id());
            if (reason != null) {
                blocked.putIfAbsent(plugin.id(), reason);
            }
        }

        // Pass 0b — no plugin may depend on an essential (security-floor) plugin. The essential plugin ships in
        // lockstep with the kernel and its version line follows the kernel's (1.5.0 with 0.5.0-beta14, back to 1.0.0
        // at rc1), so a `requires: protection >= 1.2` would silently start failing; consumers use the CaptchaService
        // SPI instead.
        for (LoadedPlugin plugin : candidates) {
            for (PluginRequirement requirement : plugin.descriptor().requirements()) {
                if (EssentialAllowlist.contains(requirement.id()) && !EssentialAllowlist.contains(plugin.id())) {
                    blocked.putIfAbsent(plugin.id(), "requires the essential plugin '" + requirement.id()
                            + "', which no plugin may depend on: it ships with Runway itself. Use the SPI it "
                            + "implements (for example CaptchaService) instead.");
                }
            }
        }

        // Pass 1 — conflicts against the full enabled set (a declared rival being present blocks the declarer).
        for (LoadedPlugin plugin : candidates) {
            for (String rival : plugin.descriptor().conflicts()) {
                if (byId.containsKey(rival)) {
                    blocked.putIfAbsent(plugin.id(), "conflicts with installed plugin '" + rival + "'");
                }
            }
        }

        // Pass 2a — a requires: cycle can never be ordered (provider must register before consumer), so the
        // whole cycle is blocked even though each requirement is individually "satisfied".
        for (LoadedPlugin plugin : candidates) {
            if (!blocked.containsKey(plugin.id()) && inCycle(plugin, byId)) {
                blocked.put(plugin.id(), "requires: cycle through '" + plugin.id() + "'");
            }
        }

        // Pass 2b — requirements to a fixpoint (cascade transitively; a dependent of a blocked plugin blocks).
        boolean changed = true;
        while (changed) {
            changed = false;
            for (LoadedPlugin plugin : candidates) {
                if (blocked.containsKey(plugin.id())) {
                    continue;
                }
                String reason = unmetRequirement(plugin, byId, blocked);
                if (reason != null) {
                    blocked.put(plugin.id(), reason);
                    changed = true;
                }
            }
        }

        List<LoadedPlugin> loadable = candidates.stream().filter(p -> !blocked.containsKey(p.id())).toList();
        return new Result(loadable, blocked);
    }

    /**
     * Why {@code plugin} is a pre-1.0-contract plugin that an SPI 1.x kernel refuses, or {@code null} when it is not:
     * a {@code mainClass} with no {@code requiresSpi}, or with a floor below {@link #MINIMUM_FLOOR}.
     */
    static String preContractReason(LoadedPlugin plugin, PluginVersion spiVersion) {
        if (plugin.isLibraryOnly()) {
            return null;
        }
        PluginVersion floor = plugin.descriptor().spiFloor();
        if (floor != null && floor.compareTo(MINIMUM_FLOOR) >= 0) {
            return null;
        }
        String declared = floor == null ? "declares no requiresSpi" : "declares requiresSpi >=" + floor;
        return "was built for the pre-1.0 plugin contract (" + declared + "; Runway SPI " + spiVersion.major()
                + ".x needs a plugin built against SPI " + MINIMUM_FLOOR_TEXT + " or later). "
                + advice(plugin, "Install the release of this plugin made for Runway 1.0, or remove it.");
    }

    /**
     * The "what to do" sentence of a descriptor-decided refusal: {@code generic} for a feature plugin, and
     * {@link EssentialAllowlist#repairAdvice} for the essential one — whose only fix is the copy bundled with the
     * running Runway, and for which "remove it" would be wrong advice (the sites would run without their abuse
     * floor). The essential plugin's refusal is the one that matters most: it puts every tenant page in fail-closed
     * mode, and the operator reads this reason in the plugins screen's row and in the boot log.
     */
    private static String advice(LoadedPlugin plugin, String generic) {
        return EssentialAllowlist.contains(plugin.id()) ? EssentialAllowlist.repairAdvice(plugin.id()) : generic;
    }

    private static String unmetRequirement(LoadedPlugin plugin, Map<String, LoadedPlugin> byId,
                                           Map<String, String> blocked) {
        for (PluginRequirement requirement : plugin.descriptor().requirements()) {
            LoadedPlugin target = byId.get(requirement.id());
            if (target == null) {
                return "requires " + describe(requirement) + " (not installed or disabled)";
            }
            if (blocked.containsKey(target.id())) {
                return "requires '" + requirement.id() + "', which is itself blocked";
            }
            if (!requirement.satisfiedBy(target.descriptor().pluginVersion())) {
                return "requires " + describe(requirement) + " but " + target.descriptor().version() + " is installed";
            }
        }
        return null;
    }

    /** Whether {@code plugin} can reach itself by following {@code requires:} edges (a dependency cycle). */
    private static boolean inCycle(LoadedPlugin plugin, Map<String, LoadedPlugin> byId) {
        return reaches(plugin, plugin.id(), byId, new HashSet<>());
    }

    private static boolean reaches(LoadedPlugin from, String targetId, Map<String, LoadedPlugin> byId,
                                   Set<String> visited) {
        for (PluginRequirement requirement : from.descriptor().requirements()) {
            LoadedPlugin next = byId.get(requirement.id());
            if (next == null) {
                continue;
            }
            if (next.id().equals(targetId)) {
                return true;
            }
            if (visited.add(next.id()) && reaches(next, targetId, byId, visited)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(PluginRequirement requirement) {
        return switch (requirement.op()) {
            case ANY -> requirement.id();
            case AT_LEAST -> requirement.id() + " >= " + requirement.version();
            case EXACT -> requirement.id() + " == " + requirement.version();
        };
    }
}
