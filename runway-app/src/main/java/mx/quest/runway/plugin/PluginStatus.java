package mx.quest.runway.plugin;

/**
 * The operator-facing badge for one installed plugin folder, computed by {@link PluginAdminService} by joining
 * the <b>frozen</b> runtime (what loaded this boot) with a <b>fresh</b> disk read + {@link PluginResolution}
 * (what would load on the next boot). The badge answers "is this running, and does the disk still agree?".
 *
 * <p>Precedence (first match wins): {@link #MALFORMED} → {@link #BLOCKED} → {@link #DISABLED} →
 * {@link #LOADED}/{@link #PENDING}. {@code PENDING} is the drift state — a folder whose next-boot fate differs
 * from what is running now (newly uploaded, re-enabled, disabled-but-still-running, or removed-but-still-running)
 * — i.e. exactly the rows that make {@link PluginAdminService#restartRequired()} true.
 */
public enum PluginStatus {

    /** Running this boot and the disk still wants it loaded — steady state, no restart pending. */
    LOADED,

    /** A drift row: its next-boot loaded-ness differs from now, so a restart is needed to apply the change. */
    PENDING,

    /** Well-formed and enabled, but {@link PluginResolution} excludes it next boot (unmet {@code requires:} / a
     *  {@code conflicts:} rival) — the reason travels in {@link PluginInstallation#blockedReason()}. */
    BLOCKED,

    /** The descriptor sets {@code enabled: false} — the operator parked it; it will not load. */
    DISABLED,

    /** The folder is present but its {@code plugin.yml} is missing/unparsable/invalid or its id ≠ folder name —
     *  the reason travels in {@link PluginInstallation#malformedReason()}. */
    MALFORMED
}
