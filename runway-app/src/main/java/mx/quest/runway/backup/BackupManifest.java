package mx.quest.runway.backup;

import java.util.List;

/**
 * The {@code manifest.yml} at the root of a site backup: what it is and what it contains. It travels with the
 * archive so a restore can identify the snapshot and know which {@link mx.quest.runway.spi.BackupParticipant}s
 * contributed data (a participant absent at restore time is tolerated — its data simply stays unrestored).
 *
 * @param siteId        the site the backup was taken from (informational — a restore targets the current site)
 * @param spiVersion    the SPI contract version at backup time (diagnostic only — restore never branches on it;
 *                      {@code formatVersion} is the field a restore checks)
 * @param createdAt     ISO-8601 instant the backup was created
 * @param participants  the participant keys whose data is present under {@code plugin-data/<key>/}
 * @param purpose       {@code RESTORE} (complete) or {@code TRANSFER} (credentials, accounts and origin
 *                      hostnames removed) — see {@link BackupService.BackupPurpose}. Absent in archives
 *                      written before the distinction existed, which are always complete, so it reads as
 *                      {@code RESTORE}: an unmarked archive must never be mistaken for a sanitised one.
 * @param formatVersion the version of the archive <b>format</b> (entry layout + manifest shape). Absent in every
 *                      archive written before the field existed, which are all format {@code 1}, so it reads as
 *                      {@code 1}. A restore accepts any version up to {@link #SUPPORTED_FORMAT_VERSION} and refuses
 *                      a higher one ({@link UnsupportedBackupFormatException}) — an archive from a newer Runway
 *                      may hold data this build would silently drop. See
 *                      {@code docs/CONFIGURATION-AND-DATA-STABILITY.md}.
 * @param omitted       what a {@code TRANSFER} copy deliberately left out as plugin personal data (SPI 0.5.0): each
 *                      declared {@code siteData.personal} path (for example {@code plugins/search/no-results.tsv};
 *                      a folder ends in {@code /}) and {@code plugins/<id>/} for each plugin that was not loaded.
 *                      Empty for a complete backup and in archives written before the key existed. An additive key:
 *                      older readers ignore it, so the format version is unchanged.
 */
public record BackupManifest(String siteId, String spiVersion, String createdAt, List<String> participants,
                             String purpose, Integer formatVersion, List<String> omitted) {

    /**
     * The archive format this build writes and the highest it restores. Raised only for a change an older reader
     * would misread (a new mandatory entry, a moved tree) — never for an additive manifest key, which older
     * readers ignore.
     */
    public static final int SUPPORTED_FORMAT_VERSION = 1;

    public BackupManifest {
        participants = participants == null ? List.of() : List.copyOf(participants);
        purpose = (purpose == null || purpose.isBlank())
                ? BackupService.BackupPurpose.RESTORE.name() : purpose.trim();
        formatVersion = formatVersion == null ? 1 : formatVersion;
        omitted = omitted == null ? List.of() : List.copyOf(omitted);
    }

    /** The pre-{@code omitted} 6-arg form: nothing omitted. */
    public BackupManifest(String siteId, String spiVersion, String createdAt, List<String> participants,
                          String purpose, Integer formatVersion) {
        this(siteId, spiVersion, createdAt, participants, purpose, formatVersion, null);
    }

    /** The pre-{@code formatVersion} 5-arg form: reads as format {@code 1}, like the archives written with it. */
    public BackupManifest(String siteId, String spiVersion, String createdAt, List<String> participants,
                          String purpose) {
        this(siteId, spiVersion, createdAt, participants, purpose, null);
    }

    /** The pre-{@code purpose} 4-arg form, kept so older call sites keep compiling as complete backups. */
    public BackupManifest(String siteId, String spiVersion, String createdAt, List<String> participants) {
        this(siteId, spiVersion, createdAt, participants, BackupService.BackupPurpose.RESTORE.name());
    }

    /** Whether this archive was produced for handing to someone else, and so is deliberately incomplete. */
    public boolean isTransfer() {
        return BackupService.BackupPurpose.TRANSFER.name().equals(purpose);
    }
}
