package mx.quest.runway.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Platform-level backup settings ({@code runway.backups.*}) — app-level, not tenant-editable (a
 * tenant-settable retention would let a tenant hoard disk or silently discard its own restore points).
 *
 * <p>This carries the {@link Retention} policy that auto-prunes stored backups after each new one is
 * written (so a site's {@code backups/<id>/} folder does not grow without bound), and the per-entry
 * decompression cap that defuses a zip-bomb archive on restore.
 */
@ConfigurationProperties("runway.backups")
public class BackupProperties {

    @NestedConfigurationProperty
    private final Retention retention = new Retention();

    @NestedConfigurationProperty
    private final Schedule schedule = new Schedule();

    /**
     * An optional <b>off-host</b> directory into which every newly-created stored backup is mirrored (organised
     * as {@code <offsite-dir>/<siteId>/<name>.zip}), so a copy survives loss of the deployment host. Point it at
     * a mounted remote share (NFS/SMB/rclone-mount) or a second disk. Blank (the default) disables mirroring —
     * backups then live only under the deployment-root {@code backups/}. The mirror is <b>best-effort</b>: if the
     * target is unwritable (share unmounted), the local backup still succeeds and a warning is logged. Off-host
     * copies are pruned by the same {@link Retention} policy as local ones.
     */
    private String offsiteDir = "";

    /** The default per-entry decompression cap when none (or a non-positive value) is configured: 256 MiB. */
    public static final long DEFAULT_MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /**
     * The maximum decompressed size of any <em>single</em> entry accepted while restoring (or cloning
     * from) a backup archive — the guard against a decompression bomb (a tiny, highly-compressible entry
     * that inflates to gigabytes) exhausting the heap or the staging disk. It bounds one entry, not the
     * whole archive, so a legitimate backup of many files is unaffected; only a pathologically large
     * single file trips it. Default: 256 MiB, generous for a real site asset (image/document/upload)
     * while still capping a bomb. Raise it if a site legitimately stores larger single files. A
     * <b>non-positive</b> value is treated as "unset" and falls back to {@link #DEFAULT_MAX_ENTRY_BYTES}
     * (a {@code 0} cap would otherwise reject every entry and break all restores).
     */
    private long maxEntryBytes = DEFAULT_MAX_ENTRY_BYTES;

    public Retention getRetention() {
        return retention;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public String getOffsiteDir() {
        return offsiteDir;
    }

    public void setOffsiteDir(String offsiteDir) {
        this.offsiteDir = offsiteDir;
    }

    public long getMaxEntryBytes() {
        return maxEntryBytes;
    }

    public void setMaxEntryBytes(long maxEntryBytes) {
        this.maxEntryBytes = maxEntryBytes;
    }

    /**
     * Rotation policy for the stored backups of a single site. Both limits apply independently and are
     * ANDed: a stored backup is pruned when it falls outside the newest {@code maxCount} <em>or</em> is
     * older than {@code maxAgeDays}. Either limit set to {@code 0} disables that dimension.
     *
     * <p>Pruning only ever removes a site's <em>own</em> stored backups (never another site's, never the
     * live site data). It runs after a successful {@code create}, so an explicit/manual backup is always
     * retained and the newest is never a pruning victim.
     */
    public static class Retention {

        /** Keep at most this many stored backups per site (newest kept); {@code 0} = unlimited. Default: 30. */
        private int maxCount = 30;

        /** Prune stored backups older than this many days; {@code 0} = no age limit. Default: 0 (off). */
        private int maxAgeDays = 0;

        public int getMaxCount() {
            return maxCount;
        }

        public void setMaxCount(int maxCount) {
            this.maxCount = maxCount;
        }

        public int getMaxAgeDays() {
            return maxAgeDays;
        }

        public void setMaxAgeDays(int maxAgeDays) {
            this.maxAgeDays = maxAgeDays;
        }
    }

    /**
     * Automatic-backup schedule. When {@code enabled}, a scheduled task backs up <b>every</b> site on the
     * {@code cron} expression, each isolated so one site's failure does not stop the rest; the retention policy
     * then prunes as usual (and off-host mirroring applies if configured). Default OFF — automatic backups are
     * opt-in; a manual backup from the console is always available regardless.
     */
    public static class Schedule {

        /** Whether the scheduled all-sites backup runs. Default: {@code false} (opt-in). */
        private boolean enabled = false;

        /**
         * The Spring cron expression (6 fields: {@code sec min hour day month weekday}) for the run. Default:
         * {@code 0 30 2 * * *} — 02:30 every day, a quiet hour. Only consulted when {@link #enabled}.
         */
        private String cron = "0 30 2 * * *";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }
    }
}
