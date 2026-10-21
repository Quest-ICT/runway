package mx.quest.runway.plugin;

import java.time.Instant;
import java.util.List;

/**
 * One row of the plugin-management read model — a single install folder, with its disk facts joined to its
 * runtime fate. Produced by {@link PluginAdminService#list()}; immutable. The UI renders {@link #status} as a
 * badge, shows {@link #blockedReason}/{@link #malformedReason} when present, warns on uninstall using
 * {@link #requiredBy}, and surfaces {@link #drift} (per-row) plus the aggregate
 * {@link PluginAdminService#restartRequired()} as the "restart required" banner.
 *
 * @param id             the folder name (= the descriptor id when well-formed)
 * @param name           the display name (descriptor {@code name}, else the id)
 * @param version        the descriptor version, or {@code "?"} when unparsable
 * @param enabledOnDisk  the descriptor's {@code enabled:} (absent = true); {@code false} when unparsable
 * @param wellFormed     the {@code plugin.yml} parses, validates, and its id equals the folder name
 * @param loaded         the plugin is running <b>this</b> boot (the frozen {@link PluginRuntime})
 * @param present        a folder for this id physically exists under {@code external/plugins}
 * @param blockedReason  why {@link PluginResolution} excludes it next boot, or {@code null}
 * @param malformedReason why the descriptor is unusable, or {@code null}
 * @param requiredBy     the ids of enabled, valid plugins that {@code requires:} this one (uninstall warning)
 * @param status         the computed badge (see {@link PluginStatus})
 * @param drift          its next-boot loaded-ness differs from now (a restart would change what runs)
 * @param hasSettings    the descriptor declares an app-level {@code settings:} schema (so a settings link shows)
 * @param essential      it is an allowlisted security-floor plugin ({@link EssentialAllowlist}); the UI hides
 *                       disable/uninstall for it and the service rejects those mutations (disabling the floor is
 *                       an "unavailable over insecure" footgun — see {@code EssentialFloor})
 * @param failures       contained failures of its contributions this boot (0 when none or not loaded)
 * @param lastError      the last contained failure's type and message, or {@code null}
 * @param lastErrorAt    when it happened, or {@code null}
 * @param degraded       contributions refused this boot (key collisions, unsupported routes)
 * @param warnings       compliance notes for this boot (since SPI 1.0 every rule violation blocks instead; what is
 *                       left is "the after-refresh check could not run")
 * @param linksHost      its own jars link host-internal classes — the "uses host internals" badge beside the
 *                       Blocked status (from disk)
 * @param quarantined    a quarantine marker blocks it (it broke a boot); the UI offers Retry
 */
public record PluginInstallation(
        String id,
        String name,
        String version,
        boolean enabledOnDisk,
        boolean wellFormed,
        boolean loaded,
        boolean present,
        String blockedReason,
        String malformedReason,
        List<String> requiredBy,
        PluginStatus status,
        boolean drift,
        boolean hasSettings,
        boolean essential,
        long failures,
        String lastError,
        Instant lastErrorAt,
        List<String> degraded,
        List<String> warnings,
        boolean linksHost,
        boolean quarantined) {

    public PluginInstallation {
        requiredBy = requiredBy == null ? List.of() : List.copyOf(requiredBy);
        degraded = degraded == null ? List.of() : List.copyOf(degraded);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The form before the health and compliance columns. */
    public PluginInstallation(String id, String name, String version, boolean enabledOnDisk, boolean wellFormed,
                              boolean loaded, boolean present, String blockedReason, String malformedReason,
                              List<String> requiredBy, PluginStatus status, boolean drift, boolean hasSettings,
                              boolean essential) {
        this(id, name, version, enabledOnDisk, wellFormed, loaded, present, blockedReason, malformedReason,
                requiredBy, status, drift, hasSettings, essential, 0, null, null, List.of(), List.of(), false, false);
    }

    /** Whether the health panel has anything to show (contained failures, refused contributions, warnings). */
    public boolean hasHealthNotes() {
        return failures > 0 || !degraded.isEmpty() || !warnings.isEmpty();
    }
}
