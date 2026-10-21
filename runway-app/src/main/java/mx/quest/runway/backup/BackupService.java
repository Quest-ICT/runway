package mx.quest.runway.backup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.event.HostWriteScope;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSiteData;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.plugin.SettingType;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteHosts;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.BackupParticipant;
import mx.quest.runway.spi.RunwaySpi;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.Yaml;
import mx.quest.runway.storage.Zips;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Per-site backup and restore — a complete, self-contained snapshot of a tenant (its data, presentation,
 * i18n, users, roles/grants, and plugin config), so it restores on any app version and renders identically
 * (the self-containment rule).
 *
 * <p>A backup is a zip: the site's <b>whole folder</b> under {@code site/} (the {@code site.yml} record,
 * {@code templates/}, {@code users.yml}, {@code roles.yml}/{@code permissions.yml}, {@code plugins/} config,
 * {@code static/}, …), each {@link BackupParticipant}'s out-of-folder data under {@code plugin-data/<key>/},
 * and a {@code manifest.yml}. Symlinks inside the site folder are <b>not followed</b> when archiving, so a
 * backup can never smuggle files from outside the tenant.
 *
 * <p><b>Restore is validated, staged, serialized and reversible.</b> Restore is per-site serialized (a
 * {@link ReentrantLock}). Entries extract into a hidden work area <b>outside the site namespace</b>
 * ({@code sites/.restore-work/<id>/staging} — a name {@link PathSafety#isSafeId} rejects, so it can never
 * collide with or be mistaken for a real tenant), every path {@linkplain PathSafety#resolveConfined
 * confined} (a zip-slip {@code ../} entry is rejected). Only once the archive is confirmed a real, non-empty
 * Runway backup (its {@code manifest.yml} and {@code site.yml} present) is the destructive swap done with
 * atomic same-filesystem renames (current → {@code .../old}, staged → live, drop old); a failure mid-swap
 * puts the original back, and a rollback that itself fails <b>keeps</b> the old copy for the startup
 * {@link BackupRestoreRecovery} sweep. A garbage, empty or non-Runway archive therefore <b>never</b> touches
 * the live site. Nor does an archive written by a <b>newer</b> Runway: the manifest's {@code formatVersion} is
 * checked the moment it is read ({@link UnsupportedBackupFormatException}; an absent field is format 1, so every
 * older archive restores). Caches are evicted immediately after the swap (before participant imports, which are
 * failure-tolerant), so no stale entry can serve the pre-restore site.
 *
 * <p><b>Zip-bomb defense.</b> Every entry is read through a per-entry decompression cap
 * ({@code runway.backups.max-entry-bytes}, {@link Zips#readBounded}/{@link Zips#copyBounded}), so a
 * crafted archive with a tiny, highly-compressible entry that inflates to gigabytes is refused past
 * the cap instead of exhausting the heap (in-memory entries: the manifest and plugin data) or the
 * staging disk (the streamed site tree). Defense-in-depth — restore is already gated to authenticated
 * operators — but the untrusted archive controls every entry name, so each read path is bounded.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final String SITE_PREFIX = "site/";
    private static final String PLUGIN_PREFIX = "plugin-data/";
    private static final String MANIFEST = "manifest.yml";
    /** Hidden restore scratch area under the sites root — a name {@code isSafeId} rejects, so never a tenant. */
    static final String WORK_DIR = ".restore-work";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());

    private final StorageLayout layout;
    private final SiteStore sites;
    private final BackupParticipantRegistry participants;
    private final ApplicationEventPublisher events;
    private final BackupProperties.Retention retention;
    /** Per-entry decompression cap applied to every archive read on restore (zip-bomb guard). */
    private final long maxEntryBytes;
    /** The installed plugins, for their declared secret keys; {@code null} in the bare test form. */
    private final RunningPlugins runningPlugins;
    /** Optional off-host mirror target ({@code runway.backups.offsite-dir}); {@code null} when disabled. */
    private final Path offsiteDir;
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public BackupService(StorageLayout layout, SiteStore sites, BackupParticipantRegistry participants,
                         ApplicationEventPublisher events) {
        this(layout, sites, participants, events, new BackupProperties());
    }

    public BackupService(StorageLayout layout, SiteStore sites, BackupParticipantRegistry participants,
                         ApplicationEventPublisher events, BackupProperties properties) {
        this(layout, sites, participants, events, properties, (RunningPlugins) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BackupService(StorageLayout layout, SiteStore sites, BackupParticipantRegistry participants,
                         ApplicationEventPublisher events, BackupProperties properties,
                         org.springframework.beans.factory.ObjectProvider<RunningPlugins> runningPlugins) {
        this(layout, sites, participants, events, properties, runningPlugins.getIfAvailable());
    }

    /**
     * Full form. {@code runningPlugins} supplies each plugin's declared secret keys for a transfer export; with
     * none, a transfer still blanks anything stored encrypted (the {@code ENC(...)} safety net).
     */
    public BackupService(StorageLayout layout, SiteStore sites, BackupParticipantRegistry participants,
                         ApplicationEventPublisher events, BackupProperties properties,
                         RunningPlugins runningPlugins) {
        this.runningPlugins = runningPlugins;
        this.layout = layout;
        this.sites = sites;
        this.participants = participants;
        this.events = events;
        this.retention = properties.getRetention();
        // A non-positive cap would reject every entry and break all restores — treat it as "use the default".
        this.maxEntryBytes = properties.getMaxEntryBytes() > 0
                ? properties.getMaxEntryBytes() : BackupProperties.DEFAULT_MAX_ENTRY_BYTES;
        String offsite = properties.getOffsiteDir();
        this.offsiteDir = (offsite == null || offsite.isBlank()) ? null : Path.of(offsite.trim());
    }

    /** A stored backup file: its name, size in bytes, and last-modified instant (ISO-8601). */
    public record StoredBackup(String name, long sizeBytes, String modifiedAt) {
    }

    // --- streaming backup / restore ----------------------------------------------------------------

    /**
     * Why an archive is being produced — the two uses a site archive is put to, which the console could not
     * previously tell apart.
     *
     * <h2>They are not the same file, and treating them as one is a trap</h2>
     * A <b>backup</b> must restore the site exactly: its sub-users and their password hashes, its per-plugin
     * credentials, the hosts it answers on. Anything less is not a backup.
     *
     * <p>A <b>transfer</b> goes to someone else — a contractor, a bug report, a published demo — and the same
     * completeness is a liability: it ships live reCAPTCHA secrets, tenant password hashes, and hostnames that
     * point back at the origin installation. Nothing in the archive marked which of the two it was, so the
     * safe use and the dangerous one were the same click.
     */
    public enum BackupPurpose {

        /** Everything, so the site comes back exactly as it was. The default, and what a scheduled backup takes. */
        RESTORE,

        /** Content and presentation, with credentials, tenant accounts and origin hostnames removed. */
        TRANSFER;

        /** Whether archives of this purpose carry secrets and accounts. */
        public boolean carriesSecrets() {
            return this == RESTORE;
        }
    }

    /** Streams a complete backup of {@code siteId} to {@code out} (the caller owns and closes {@code out}). */
    public void backup(String siteId, OutputStream out) throws IOException {
        backup(siteId, out, BackupPurpose.RESTORE);
    }

    /**
     * Streams an archive of {@code siteId} to {@code out} for the given {@code purpose}.
     *
     * <p>{@link BackupPurpose#TRANSFER} omits the tenant's accounts and permission deltas, blanks every value
     * its plugins declare as a secret, neutralises the hostnames in {@code site.yml}, drops traffic data, and
     * skips participant exports (a plugin's out-of-folder state — search indexes, form submissions — which is
     * either regenerable or personal data that must not travel). It also leaves out <b>plugin personal data</b>
     * ({@link #transferOmissions}): every path a loaded plugin declares under {@code siteData.personal}, and the
     * whole {@code plugins/<id>/} folder of any plugin that is not loaded (nobody can say what in it is personal),
     * and the pending editorial work under {@code templates/.pending/} ({@link #pendingWorkOmission}: reviewer
     * names and notes, unpublished drafts). The manifest's {@code omitted} list names what was left out. What remains is the site's content,
     * presentation and settings: enough to stand it up somewhere else, and not enough to impersonate it.
     *
     * <p>A transfer archive restores through the ordinary restore path; the site simply comes up with no
     * sub-users (its assigned tenant superadmin still reaches it) and its secrets unset.
     *
     * @param siteId  the tenancy to archive
     * @param out     the stream to write to (the caller owns and closes it)
     * @param purpose whether this archive is for restoring this site or handing to someone else
     * @throws IOException if the archive cannot be written
     */
    public void backup(String siteId, OutputStream out, BackupPurpose purpose) throws IOException {
        requireSafe(siteId);
        ReentrantLock lock = lockFor(siteId);
        lock.lock();
        try {
            Path siteDir = layout.siteDir(siteId);
            BufferedOutputStream buffered = new BufferedOutputStream(out);
            ZipOutputStream zip = new ZipOutputStream(buffered);

            List<String> keys = purpose.carriesSecrets()
                    ? participants.all().stream().map(BackupParticipant::key).sorted().toList()
                    : List.of();
            Map<String, PluginSiteData> declarations = loadedSiteData();
            List<String> omitted = purpose.carriesSecrets() ? List.of() : omissions(siteDir, declarations);
            zip.putNextEntry(new ZipEntry(MANIFEST));
            Yaml.write(zip, new BackupManifest(siteId, RunwaySpi.VERSION, Instant.now().toString(), keys,
                    purpose.name(), BackupManifest.SUPPORTED_FORMAT_VERSION, omitted));
            zip.closeEntry();

            if (Files.isDirectory(siteDir)) {
                zipTree(zip, siteDir, purpose, declarations);
            }
            if (purpose.carriesSecrets()) {
                SiteRef ref = new SiteRef(siteId);
                for (BackupParticipant participant : participants.all()) {
                    // Deliberately NOT under the containment guard: a participant's export failure fails the whole backup
                    // (a backup is complete or it is not a backup). The message names the participant and its
                    // plugin, so the operator knows which plugin to fix, disable or report.
                    try {
                        participant.export(new ZipBackup.Writer(zip, participant.key()), ref);
                    } catch (IOException | RuntimeException failure) {
                        throw new IOException("backup participant '" + participant.key() + "' (plugin '"
                                + participants.guard().pluginOf(participant) + "') failed to export site '" + siteId
                                + "': " + failure.getMessage(), failure);
                    }
                }
            }
            zip.finish();      // finalize the archive without closing the caller's stream
            buffered.flush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Restores {@code siteId} from the archive {@code in} — a validated, reversible replacement of the site.
     * A garbage / empty / non-Runway archive is rejected without touching the live site.
     *
     * @throws UnsupportedBackupFormatException if the archive declares a newer format than this build restores
     *                                          (the live site is untouched)
     * @throws IOException if the archive is not a valid Runway backup, is unreadable, or a zip entry escapes
     */
    public void restore(String siteId, InputStream in) throws IOException {
        requireSafe(siteId);
        ReentrantLock lock = lockFor(siteId);
        // The write scope wraps the lock (HostWriteScope): plugin event callbacks for RESTORED run once the lock is
        // released — and after the participant imports, so a plugin reacting to the restore sees its data in place.
        try (HostWriteScope scope = HostWriteScope.enter()) {
            lock.lock();
            try {
                doRestore(siteId, in);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Whether the current thread holds {@code siteId}'s backup lock — a test hook for the guarantee that plugin event
     * callbacks never run under a host lock.
     */
    public boolean isSiteLockHeldByCurrentThread(String siteId) {
        ReentrantLock lock = locks.get(siteId);
        return lock != null && lock.isHeldByCurrentThread();
    }

    private void doRestore(String siteId, InputStream in) throws IOException {
        Staged staged = stageAndSwap(siteId, in);
        releaseHostsClaimedElsewhere(siteId);
        // The on-disk swap is what the caches track — evict + fan out RESTORED NOW, before the (failure-tolerant)
        // participant imports, so a participant error can never leave stale caches serving the pre-restore site.
        sites.evict(siteId);
        events.publishEvent(new SiteChangedEvent(siteId, SiteChangedEvent.Kind.RESTORED));
        importParticipants(siteId, staged.pluginData());
        deleteRecursively(staged.work());
        log.info("restored site '{}' from backup", siteId);
    }

    /**
     * A backup carries the hosts its site answered on when it was taken. Another site may have been given one of
     * them since, and two sites must never answer on one host, so a restore keeps the hosts that are still free and
     * drops the others, with a warning naming them. The operator re-adds a dropped host with Edit hosts once it
     * is free.
     */
    private void releaseHostsClaimedElsewhere(String siteId) {
        sites.evict(siteId);
        Site restored;
        try {
            restored = sites.find(siteId).orElse(null);
        } catch (RuntimeException unreadable) {
            return; // an unreadable record routes nowhere, so it claims nothing; the restore itself goes on
        }
        if (restored == null) {
            return;
        }
        synchronized (SiteHosts.CLAIMS) {
            List<String> taken = SiteHosts.claimedElsewhere(sites, siteId, restored.hosts());
            if (taken.isEmpty()) {
                return;
            }
            List<String> kept = restored.hosts().stream().filter(host -> !taken.contains(host)).toList();
            sites.save(restored.withHosts(kept));
            log.warn("restored site '{}' without host(s) {}: another site answers on them now", siteId, taken);
        }
    }

    /**
     * Creates a <b>new</b> site {@code newId} from an uploaded backup — the inverse of restore. The archive is
     * staged into the new folder, then its {@code site.yml} is rewritten to the new identity ({@code newId} +
     * {@code name} + {@code hosts}) <em>before any observer sees it</em>, so the clone is fully independent of
     * the source site (which may still exist). Locales and base URL are carried over from the backup.
     *
     * @throws IllegalStateException if a site with {@code newId} already exists
     * @throws IllegalArgumentException if a host is malformed or already belongs to another site ({@link SiteHosts})
     * @throws UnsupportedBackupFormatException if the archive declares a newer format than this build restores
     *                                          (nothing is created)
     * @throws IOException           if the archive is not a valid Runway backup
     */
    public Site createFromBackup(String newId, String name, List<String> hosts, InputStream in) throws IOException {
        requireSafe(newId);
        // Refuse a malformed or taken host before staging the upload; the claim is checked again at the save.
        List<String> normalizedHosts = SiteHosts.normalize(hosts);
        SiteHosts.requireUnclaimed(sites, newId, normalizedHosts);
        hosts = normalizedHosts;
        // The write scope wraps the lock (HostWriteScope): plugin event callbacks run once it is released.
        try (HostWriteScope scope = HostWriteScope.enter()) {
            return createFromBackupLocked(newId, name, hosts, in);
        }
    }

    private Site createFromBackupLocked(String newId, String name, List<String> hosts, InputStream in)
            throws IOException {
        ReentrantLock lock = lockFor(newId);
        lock.lock();
        try {
            // Inside the lock: a concurrent create of the same id can't slip past the guard and clobber the
            // site the first one just wrote (the swap would otherwise move a live folder aside and replace it).
            if (sites.exists(newId)) {
                throw new IllegalStateException("a site with id '" + newId + "' already exists");
            }
            Staged staged = stageAndSwap(newId, in);
            try {
                // The staged site.yml still carries the SOURCE identity — no event has fired, so rewrite it now.
                Site source = sites.find(newId).orElseThrow(() -> new IOException("restored site.yml is unreadable"));
                String siteName = (name == null || name.isBlank()) ? source.name() : name.strip();
                // The editorial level travels: a site that required review keeps requiring it in its copy.
                Site created = new Site(newId, siteName, hosts,
                        source.defaultLocale(), source.locales(), source.baseUrl(), SiteStatus.ACTIVE, false, null,
                        source.editorial());
                synchronized (SiteHosts.CLAIMS) {
                    // Another site may have claimed a host while the upload was staged.
                    SiteHosts.requireUnclaimed(sites, newId, hosts);
                    sites.save(created); // overwrite site.yml with the new identity (invalidates the newId cache)
                }
                sites.evict(newId);
                // A backup import: mark it so birth-time default seeders (e.g. the favicon) stand down — an
                // imported site must be a faithful copy of its backup, never gaining a default the source lacked.
                events.publishEvent(SiteChangedEvent.createdFromBackup(newId));
                importParticipants(newId, staged.pluginData());
                log.info("created site '{}' from an uploaded backup", newId);
                return created;
            } catch (IOException | RuntimeException failed) {
                // The backup's site.yml didn't yield a valid site AFTER the swap — undo the half-created folder
                // so no orphan (carrying the source id) is left behind, and no phantom blocks a retry.
                sites.evict(newId);
                deleteRecursively(layout.siteDir(newId));
                throw failed;
            } finally {
                deleteRecursively(staged.work());
            }
        } finally {
            lock.unlock();
        }
    }

    /** The result of staging a backup: the manifest, the plugin data to import, and the work dir to clean up. */
    private record Staged(BackupManifest manifest, Map<String, Map<String, byte[]>> pluginData, Path work) {
    }

    /**
     * Extracts + validates the archive into a staging dir and atomically swaps it into {@code siteId}'s folder.
     * Publishes no event and imports no participant data — the caller owns the lifecycle (restore vs create),
     * so create can rewrite the identity before anything observes the swapped-in files.
     */
    private Staged stageAndSwap(String siteId, InputStream in) throws IOException {
        Path siteDir = layout.siteDir(siteId);
        Path work = workDir(siteId);
        Path staging = work.resolve("staging");
        Path old = work.resolve("old");
        deleteRecursively(work);
        Files.createDirectories(staging);
        // TOCTOU guard: between the delete above and this create, a local attacker could plant a symlink at
        // `work`/`staging` and redirect the extraction outside the storage tree (resolveConfined below is lexical
        // and would not notice). Verify the freshly-created staging really lives under the work root — a planted
        // symlink makes its real path escape that prefix, and we abort before writing a single entry.
        Path expectedWorkRoot = layout.sites().toRealPath().resolve(WORK_DIR);
        if (!staging.toRealPath().startsWith(expectedWorkRoot)) {
            deleteRecursively(work);
            throw new IOException("restore staging resolved outside the work root — refusing (possible symlink attack)");
        }

        Map<String, Map<String, byte[]>> pluginData = new LinkedHashMap<>();
        BackupManifest manifest = null;
        int stagedFiles = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.equals(MANIFEST)) {
                    // Bound the manifest read too — a bomb wearing manifest.yml's name would otherwise OOM the parser.
                    manifest = Yaml.read(new ByteArrayInputStream(Zips.readBounded(zip, maxEntryBytes)),
                            BackupManifest.class);
                    // Refuse a newer archive format as soon as we know it — before streaming the rest to disk.
                    requireSupportedFormat(manifest);
                } else if (name.startsWith(SITE_PREFIX)) {
                    Path target = PathSafety.resolveConfined(staging, name.substring(SITE_PREFIX.length()));
                    Files.createDirectories(target.getParent());
                    // Stream to disk under the per-entry cap so a highly-compressible entry can't fill the staging disk.
                    try (OutputStream fileOut = Files.newOutputStream(target)) {
                        Zips.copyBounded(zip, fileOut, maxEntryBytes);
                    }
                    restoreModificationTime(target, entry);
                    stagedFiles++;
                } else if (name.startsWith(PLUGIN_PREFIX)) {
                    // Read into memory under the per-entry cap — the heap-exhaustion vector this guard closes.
                    collectPluginEntry(pluginData, name.substring(PLUGIN_PREFIX.length()),
                            Zips.readBounded(zip, maxEntryBytes));
                } else {
                    // Unrecognized entry: bound-drain it. Leaving it unread makes the next getNextEntry() inflate
                    // the whole (possibly bomb) entry while skipping — an unbounded CPU/time DoS past validation.
                    Zips.copyBounded(zip, OutputStream.nullOutputStream(), maxEntryBytes);
                }
            }
        } catch (RuntimeException | IOException ex) {
            deleteRecursively(work);
            throw ex;
        }

        // VALIDATE before the destructive swap — a recognizable, non-empty Runway backup. A garbage / empty /
        // non-Runway archive stages no manifest or no site.yml, so it is rejected with the live site untouched.
        if (manifest == null || stagedFiles == 0 || !Files.isRegularFile(staging.resolve("site.yml"))) {
            deleteRecursively(work);
            throw new IOException("not a valid Runway site backup (missing manifest.yml or site content)");
        }
        warnParticipantDrift(siteId, manifest, pluginData);
        swapIntoPlace(siteDir, staging, old);
        return new Staged(manifest, pluginData, work);
    }

    /**
     * The format gate: an archive with no {@code formatVersion} is format 1 (every archive written before the field
     * existed); anything up to {@link BackupManifest#SUPPORTED_FORMAT_VERSION} restores; a higher version was
     * written by a newer Runway and is refused, because this build could silently drop what it does not
     * understand. Below 1 is not a version any Runway writes, so the archive is not a valid backup.
     */
    private static void requireSupportedFormat(BackupManifest manifest) throws IOException {
        if (manifest == null) {
            return; // an empty manifest entry: rejected after staging as "not a valid Runway site backup"
        }
        int format = manifest.formatVersion();
        if (format > BackupManifest.SUPPORTED_FORMAT_VERSION) {
            throw new UnsupportedBackupFormatException(format, BackupManifest.SUPPORTED_FORMAT_VERSION);
        }
        if (format < 1) {
            throw new IOException("not a valid Runway site backup (formatVersion " + format + ")");
        }
    }

    /** Imports each plugin participant's data (failure-tolerant — a bad participant never fails the restore). */
    private void importParticipants(String siteId, Map<String, Map<String, byte[]>> pluginData) {
        SiteRef ref = new SiteRef(siteId);
        for (BackupParticipant participant : participants.all()) {
            try {
                participant.importInto(new ZipBackup.Reader(pluginData.getOrDefault(participant.key(), Map.of())), ref);
            } catch (RuntimeException | LinkageError | IOException e) {
                // Contained: the site files ARE restored; this participant's data is not. Logged (with the stack, once a
                // minute per participant), counted against its plugin, and the next participant still runs.
                participants.guard().record(participant, ContributionGuard.Axis.BACKUP_IMPORT, participant.key(), e);
            }
        }
    }

    // --- stored backups (deployment-root backups/<siteId>/) ---------------------------------------

    /** Creates a backup file under the deployment-root {@code backups/<siteId>/} and returns its name. */
    public String create(String siteId) throws IOException {
        requireSafe(siteId);
        Path dir = backupDir(siteId);
        Files.createDirectories(dir);
        String name = "backup-" + STAMP.format(Instant.now()) + ".zip";
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            backup(siteId, out);
        }
        log.info("created backup '{}' for site '{}'", name, siteId);
        prune(siteId);
        mirrorOffsite(siteId, name, file);
        return name;
    }

    /**
     * Copies a freshly-written backup to the configured off-host directory ({@code <offsite-dir>/<siteId>/<name>})
     * and prunes the off-host copies under the same retention. <b>Best-effort:</b> a failure (target unmounted /
     * read-only) is logged and swallowed — the local backup already succeeded, so an off-host hiccup must not
     * report the backup as failed. No-op when no off-host directory is configured.
     */
    private void mirrorOffsite(String siteId, String name, Path localFile) {
        if (offsiteDir == null) {
            return;
        }
        try {
            Path dir = PathSafety.resolveWithin(offsiteDir, siteId); // siteId already validated safe
            Files.createDirectories(dir);
            Files.copy(localFile, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            log.info("mirrored backup '{}' for site '{}' to off-host {}", name, siteId, dir);
            pruneOffsiteDir(dir, siteId);
        } catch (IOException | RuntimeException e) {
            log.warn("off-host backup mirror failed for site '{}' (local backup succeeded): {}", siteId, e.toString());
        }
    }

    /** Applies the retention policy to the off-host copies of one site (same rules as {@link #prune}). */
    private void pruneOffsiteDir(Path dir, String siteId) {
        if (retention.getMaxCount() <= 0 && retention.getMaxAgeDays() <= 0) {
            return;
        }
        List<Path> zips;
        try (Stream<Path> entries = Files.list(dir)) {
            zips = entries.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()) // newest first
                    .toList();
        } catch (IOException e) {
            log.warn("could not list off-host backups for site '{}' to prune: {}", siteId, e.toString());
            return;
        }
        List<Path> victims = new java.util.ArrayList<>();
        int max = retention.getMaxCount();
        if (max > 0 && zips.size() > max) {
            victims.addAll(zips.subList(max, zips.size()));
        }
        int ageDays = retention.getMaxAgeDays();
        if (ageDays > 0) {
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(ageDays));
            for (Path p : zips) {
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff) && !victims.contains(p)) {
                        victims.add(p);
                    }
                } catch (IOException ignored) {
                    // an unreadable mtime just means we don't age-prune that file this pass
                }
            }
        }
        for (Path victim : victims) {
            try {
                if (Files.deleteIfExists(victim)) {
                    log.info("pruned off-host backup '{}' for site '{}'", victim.getFileName(), siteId);
                }
            } catch (IOException e) {
                log.warn("could not prune off-host backup '{}': {}", victim, e.toString());
            }
        }
    }

    /**
     * Applies the {@link BackupProperties.Retention} policy to a site's stored backups: drops those beyond
     * the newest {@code maxCount}, then those older than {@code maxAgeDays}. The just-written backup is the
     * newest and is therefore never pruned. Best-effort — a prune failure is logged, never propagated, so a
     * successful backup is never reported as failed because cleanup of an <em>old</em> file hiccuped.
     *
     * <p>Runs under the per-site lock so a concurrent {@code create}/{@code restore} on the same site cannot
     * race the deletions.
     */
    private void prune(String siteId) {
        if (retention.getMaxCount() <= 0 && retention.getMaxAgeDays() <= 0) {
            return; // both dimensions off — accumulate without bound (the pre-retention behaviour)
        }
        ReentrantLock lock = lockFor(siteId);
        lock.lock();
        try {
            List<StoredBackup> stored = list(siteId); // newest first
            List<StoredBackup> victims = new java.util.ArrayList<>();
            int max = retention.getMaxCount();
            if (max > 0 && stored.size() > max) {
                victims.addAll(stored.subList(max, stored.size()));
            }
            int ageDays = retention.getMaxAgeDays();
            if (ageDays > 0) {
                Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(ageDays));
                for (StoredBackup b : stored) {
                    if (Instant.parse(b.modifiedAt()).isBefore(cutoff) && !victims.contains(b)) {
                        victims.add(b);
                    }
                }
            }
            for (StoredBackup victim : victims) {
                if (delete(siteId, victim.name())) {
                    log.info("pruned stored backup '{}' for site '{}' (retention policy)", victim.name(), siteId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("backup retention prune failed for site '{}' (backup itself succeeded)", siteId, e);
        } finally {
            lock.unlock();
        }
    }

    /** The stored backups for a site, newest first. */
    public List<StoredBackup> list(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            return List.of();
        }
        Path dir = backupDir(siteId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .map(BackupService::describe)
                    .sorted(Comparator.comparing(StoredBackup::name).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing backups for " + siteId, e);
        }
    }

    /** The path to a stored backup for download, or empty if the name is unsafe or the file is absent. */
    public Optional<Path> file(String siteId, String name) {
        Path resolved = resolveBackup(siteId, name);
        return (resolved != null && Files.isRegularFile(resolved)) ? Optional.of(resolved) : Optional.empty();
    }

    /** Deletes a stored backup; {@code true} if one was removed. */
    public boolean delete(String siteId, String name) {
        Path resolved = resolveBackup(siteId, name);
        if (resolved == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException("deleting backup " + name, e);
        }
    }

    /** Restores a site from one of its stored backups. */
    public void restoreStored(String siteId, String name) throws IOException {
        Path resolved = resolveBackup(siteId, name);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            throw new IOException("no such backup: " + name);
        }
        try (InputStream in = Files.newInputStream(resolved)) {
            restore(siteId, in);
        }
    }

    // --- internals ---------------------------------------------------------------------------------

    /** Archives every regular file under {@code root}, never following a symlink out of the tenant folder. */
    private void zipTree(ZipOutputStream zip, Path root, BackupPurpose purpose,
                         Map<String, PluginSiteData> declarations) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) { // Files.walk does not follow directory symlinks by default
            for (Path file : walk.sorted().toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue; // skip directories, symlinks (even to regular files), and special files
                }
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (isTransient(rel)) {
                    continue; // logs and usage stats are transient — they never travel in a content backup
                }
                if (!purpose.carriesSecrets() && (isWithheldFromTransfer(rel) || pendingWorkOmission(rel) != null
                        || personalDataOmission(rel, declarations) != null)) {
                    continue;
                }
                byte[] sanitised = purpose.carriesSecrets() ? null : sanitiseForTransfer(rel, file);
                ZipEntry entry = new ZipEntry(SITE_PREFIX + rel);
                // Carry the file's modification time in the archive. A page's <lastmod> in the sitemap derives
                // from its mtime, so a restore that stamped everything "now" told every search engine that the
                // whole site had just been rewritten — the one signal a sitemap exists to give, made false by
                // an operation that changed no content at all. (Also fixes the flip side: a restored site
                // looked freshly edited in the console's own listings.)
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                zip.putNextEntry(entry);
                if (sanitised != null) {
                    zip.write(sanitised);
                } else {
                    Files.copy(file, zip);
                }
                zip.closeEntry();
            }
        }
    }

    /** Transient per-site data (access/audit logs, bandwidth stats) excluded from the content backup. */
    private static boolean isTransient(String rel) {
        return rel.equals("logs") || rel.startsWith("logs/") || rel.equals("stats") || rel.startsWith("stats/");
    }

    private static void collectPluginEntry(Map<String, Map<String, byte[]>> into, String rest, byte[] bytes) {
        int slash = rest.indexOf('/');
        if (slash > 0 && slash < rest.length() - 1) {
            into.computeIfAbsent(rest.substring(0, slash), k -> new LinkedHashMap<>())
                    .put(rest.substring(slash + 1), bytes);
        }
    }

    private void warnParticipantDrift(String siteId, BackupManifest manifest, Map<String, Map<String, byte[]>> data) {
        for (String key : manifest.participants()) {
            if (participants.find(key).isEmpty()) {
                log.warn("backup of '{}' names participant '{}' which is not installed — its data stays unrestored",
                        siteId, key);
            }
        }
        for (String key : data.keySet()) {
            if (participants.find(key).isEmpty()) {
                log.warn("backup of '{}' carries plugin-data for absent participant '{}'", siteId, key);
            }
        }
    }

    /** Atomic same-filesystem swap: current → old, staged → live, drop old; restore the original on failure. */
    private static void swapIntoPlace(Path siteDir, Path staging, Path old) throws IOException {
        try {
            if (Files.exists(siteDir)) {
                move(siteDir, old);
            }
            move(staging, siteDir);
        } catch (IOException swapFailed) {
            if (Files.exists(old) && !Files.exists(siteDir)) {
                try {
                    move(old, siteDir); // roll back
                } catch (IOException rollbackFailed) {
                    // Leave `old` in place as the recovery copy — the startup sweep rolls it forward. Do NOT
                    // delete it (it now holds the ONLY copy of the original site).
                    swapFailed.addSuppressed(rollbackFailed);
                    throw swapFailed;
                }
            }
            throw swapFailed;
        }
        deleteRecursively(old);
    }

    /** A same-filesystem rename, atomic where the filesystem supports it. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static StoredBackup describe(Path file) {
        try {
            return new StoredBackup(file.getFileName().toString(), Files.size(file),
                    Files.getLastModifiedTime(file).toInstant().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("reading backup metadata " + file, e);
        }
    }

    private ReentrantLock lockFor(String siteId) {
        return locks.computeIfAbsent(siteId, k -> new ReentrantLock());
    }

    private Path workDir(String siteId) {
        return layout.sites().resolve(WORK_DIR).resolve(siteId);
    }

    private Path backupDir(String siteId) {
        return PathSafety.resolveConfined(layout.backups(), siteId);
    }

    /** Resolves a stored backup path, or {@code null} if the site id or name is unsafe. */
    private Path resolveBackup(String siteId, String name) {
        if (!PathSafety.isSafeId(siteId) || name == null || !name.endsWith(".zip")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            return null;
        }
        return PathSafety.resolveConfined(backupDir(siteId), name);
    }

    private static void requireSafe(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("deleting " + path, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("walking " + dir, e);
        }
    }

    /**
     * Restores a staged file's modification time from its archive entry, when the archive carries one.
     *
     * <p>Best-effort by design: an archive written before entry times were stored, or one produced by another
     * tool, simply has none — and a filesystem may refuse the call. Neither is a reason to fail a restore, so
     * the file keeps its "now" stamp and the restore proceeds. Failing here would trade a cosmetic freshness
     * signal for the operator's actual data recovery, which is the wrong way round.
     *
     * <p>Set on the staged copy rather than after the swap, so the times are already correct at the moment the
     * site becomes visible — there is no window where a restored site advertises itself as freshly rewritten.
     */
    private static void restoreModificationTime(Path target, ZipEntry entry) {
        java.nio.file.attribute.FileTime modified = entry.getLastModifiedTime();
        if (modified == null) {
            return;
        }
        try {
            Files.setLastModifiedTime(target, modified);
        } catch (IOException ignored) {
            log.debug("could not restore the modification time of {} — keeping the current one", target);
        }
    }

    // --- transfer sanitisation ----------------------------------------------------------------------

    /**
     * What a transfer copy of {@code siteId} would leave out <b>as personal data or unpublished work</b> if it were
     * made now: each declared personal path that exists (as declared — a file, or a folder ending in {@code /}),
     * {@code plugins/<id>/} for each plugin folder whose plugin is not loaded, and {@code templates/.pending/} when
     * any pending revision or review record exists. Sorted; empty when there is nothing of the kind. Shown on the backup screen, and recorded as the manifest's {@code omitted} list in the copy itself.
     *
     * @throws IllegalArgumentException if {@code siteId} is unsafe
     * @throws IOException              if the site folder cannot be walked
     */
    public List<String> transferOmissions(String siteId) throws IOException {
        requireSafe(siteId);
        return omissions(layout.siteDir(siteId), loadedSiteData());
    }

    /** The {@code siteData} declaration of every plugin loaded this boot, by id; empty in the bare test form. */
    private Map<String, PluginSiteData> loadedSiteData() {
        Map<String, PluginSiteData> out = new LinkedHashMap<>();
        if (runningPlugins != null) {
            for (LoadedPlugin plugin : runningPlugins.installed()) {
                out.put(plugin.id(), plugin.descriptor().siteData());
            }
        }
        return out;
    }

    private List<String> omissions(Path siteDir, Map<String, PluginSiteData> declarations) throws IOException {
        if (!Files.isDirectory(siteDir)) {
            return List.of();
        }
        java.util.TreeSet<String> omitted = new java.util.TreeSet<>();
        try (Stream<Path> walk = Files.walk(siteDir)) {
            for (Path file : walk.toList()) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    String rel = siteDir.relativize(file).toString().replace('\\', '/');
                    String label = pendingWorkOmission(rel);
                    if (label == null) {
                        label = personalDataOmission(rel, declarations);
                    }
                    if (label != null) {
                        omitted.add(label);
                    }
                }
            }
        }
        return List.copyOf(omitted);
    }

    /**
     * Why {@code rel} (a path in the site folder) is left out of a transfer copy as plugin personal data — the label
     * the manifest records: the covering declaration (for example {@code plugins/<id>/submissions.tsv}), or
     * {@code plugins/<id>/} for a plugin that is not loaded — or {@code null} to keep it. Only files inside a
     * plugin's subtree are concerned; the plugin's settings file ({@code plugins/<id>.yml}) keeps the secret-blanking
     * rule instead.
     */
    static String personalDataOmission(String rel, Map<String, PluginSiteData> declarations) {
        if (!rel.startsWith("plugins/")) {
            return null;
        }
        String rest = rel.substring("plugins/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null; // plugins/<id>.yml, or a stray file directly in plugins/
        }
        String pluginId = rest.substring(0, slash);
        PluginSiteData data = declarations.get(pluginId);
        if (data == null) {
            return "plugins/" + pluginId + "/"; // fail-safe: an unloaded plugin's folder cannot be judged, so it stays
        }
        String declared = data.declarationCovering(rest.substring(slash + 1));
        return declared == null ? null : "plugins/" + pluginId + "/" + declared;
    }

    /** The label a transfer copy's manifest records for the left-out pending editorial work. */
    static final String PENDING_OMISSION = "templates/" + mx.quest.runway.page.PendingRevisionStore.PENDING_DIR + "/";

    /**
     * {@link #PENDING_OMISSION} when {@code rel} lies under {@code templates/.pending/} — or {@code null} to keep it.
     *
     * <p>A transfer copy leaves out <b>all pending editorial work</b>: each {@code review.yml} names site accounts
     * ({@code submittedBy}, {@code returnedBy} — often email addresses) and carries the reviewer's free-text note,
     * and the revisions themselves are unpublished drafts. The recipient gets the site as it is published; a full
     * backup still carries {@code .pending} so a restore brings the review queue back. Listed in the manifest's
     * {@code omitted} like plugin personal data, so the recipient can see it was withheld rather than absent.
     */
    static String pendingWorkOmission(String rel) {
        return rel.startsWith(PENDING_OMISSION) ? PENDING_OMISSION : null;
    }

    /** Tenant files that never travel in a transfer archive, and why each one is dangerous to hand over. */
    private static boolean isWithheldFromTransfer(String rel) {
        return rel.equals("users.yml")            // sub-user records — including their password hashes
                || rel.equals("permissions.yml")  // grants naming users the recipient will not have anyway
                || rel.equals("bandwidth.csv");   // traffic history: the recipient's business, not theirs
    }

    /**
     * The bytes to write for {@code rel} in a transfer archive, or {@code null} to copy the file unchanged.
     *
     * <p>Two files carry values that are either secret or specific to the origin installation:
     * <ul>
     *   <li>{@code plugins/<id>.yml} — every value the plugin <b>declares</b> as a secret, plus anything
     *       already stored encrypted, is blanked. Declared rather than guessed: matching key names like
     *       {@code *key} or {@code *secret} would miss whatever the next plugin calls its credential, and a
     *       sanitiser that misses one is worse than none, because it is trusted.</li>
     *   <li>{@code site.yml} — the hostnames and base URL, which otherwise point the recipient's install back
     *       at the origin's domain (and, if they ever go live, at traffic that is not theirs).</li>
     * </ul>
     *
     * <p>Keys are blanked rather than removed, so the shape of the file still shows the recipient what has to
     * be filled in — an absent key looks like a setting that does not exist.
     *
     * <p>Unknown keys are preserved: per-site plugin config may carry hand-edited entries the current schema
     * does not describe, and a transfer must not quietly discard an operator's data.
     */
    private byte[] sanitiseForTransfer(String rel, Path file) throws IOException {
        if (rel.equals("site.yml")) {
            return neutraliseSiteRecord(file);
        }
        if (rel.startsWith("plugins/") && rel.endsWith(".yml")) {
            return blankDeclaredSecrets(rel.substring("plugins/".length(), rel.length() - 4), file);
        }
        return null;
    }

    /**
     * Rewrites {@code site.yml} with placeholder hostnames and base URL.
     *
     * <p>Derived from the site id rather than a constant, so two sites transferred from the same installation
     * do not collide when both are imported side by side. {@code example.com} is reserved for exactly this
     * (RFC 2606), so a placeholder that is accidentally left in place resolves nowhere instead of at somebody
     * else's real domain.
     */
    private byte[] neutraliseSiteRecord(Path file) throws IOException {
        Map<String, Object> record = readYamlMap(file);
        if (record == null) {
            return null;                       // unreadable: copy it through rather than lose the file
        }
        Object id = record.get("id");
        String placeholder = (id == null ? "site" : id.toString().trim()) + ".example.com";
        if (record.containsKey("hosts")) {
            record.put("hosts", List.of(placeholder));
        }
        if (record.containsKey("baseUrl")) {
            record.put("baseUrl", "https://" + placeholder);
        }
        return writeYaml(record);
    }

    /**
     * Blanks every value in a per-site plugin config that the plugin declares as a secret, plus any value
     * already stored encrypted.
     *
     * <p>The encrypted check is the safety net for the case the declaration cannot cover: the plugin is not
     * installed on this instance (so its schema is unavailable), or it was renamed. An {@code ENC(...)} value
     * is self-identifying, and it is exactly the shape a credential takes here — so even with no schema at
     * all, a stored secret does not leave.
     */
    private byte[] blankDeclaredSecrets(String pluginId, Path file) throws IOException {
        Map<String, Object> config = readYamlMap(file);
        if (config == null) {
            return null;
        }
        List<String> blanked = new java.util.ArrayList<>();
        blankSecretsIn(config, "", declaredSecretKeys(pluginId), blanked);
        if (blanked.isEmpty()) {
            return null;
        }
        log.info("transfer export: blanked {} secret value(s) in {}: {}", blanked.size(), file.getFileName(),
                blanked);
        return writeYaml(config);
    }

    /**
     * Blanks every secret value in {@code node}, <b>at any depth</b>, recording each path blanked.
     *
     * <h2>Why this recurses, and why it did not at first</h2>
     * The first version walked only the top level, on the assumption that a per-site plugin config is a flat
     * map of dotted keys. That is the shape the <em>generic settings form</em> writes — and it is not the
     * shape a plugin with its own store writes. The bundled protection plugin persists a nested record
     * ({@code captcha:} &rarr; {@code secretKey: ENC(...)}), declares no {@code siteSettings} schema at all,
     * and was therefore copied into transfer archives <b>verbatim</b>: neither the declared-key check nor the
     * {@code ENC(} check could see a value one level down.
     *
     * <p>That is the failure this whole feature exists to prevent, and it was invisible because the test
     * fixture was written by hand in the flat shape rather than taken from what the product actually stores.
     *
     * <h2>Four independent nets, because any one of them can miss</h2>
     * <ol>
     *   <li><b>Declared</b> — the plugin's {@code siteSettings} {@code type: secret} keys, matched against
     *       both the full dotted path and the leaf name, so a schema written either way is honoured.</li>
     *   <li><b>Encrypted</b> — any {@code ENC(...)} string. Self-identifying, so it works with no schema.</li>
     *   <li><b>Named</b> — a leaf whose name contains {@code secret}, {@code password}, {@code passwd},
     *       {@code token}, {@code credential}, {@code apikey} or {@code privatekey}. Deliberately NOT a bare
     *       {@code key}: {@code siteKey}, {@code publicKey} and {@code sortKey} are not secrets, and blanking
     *       a working setting is its own kind of harm. These words essentially never name a non-secret, and
     *       this net is what covers a plugin that stores a secret in PLAINTEXT and declares nothing — which
     *       neither of the nets above can see.</li>
     *   <li><b>Recursion into lists</b>, so a secret inside a sequence of maps is not missed either.</li>
     * </ol>
     *
     * <p>Every blank is logged with its path. A sanitiser is trusted the moment it exists, so what it did —
     * and by implication what it did not do — has to be visible to the operator who is about to hand the file
     * over.
     */
    @SuppressWarnings("unchecked")
    private static void blankSecretsIn(Map<String, Object> node, String prefix, Set<String> declared,
                                       List<String> blanked) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String leaf = entry.getKey();
            String path = prefix.isEmpty() ? leaf : prefix + "." + leaf;
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> child) {
                blankSecretsIn((Map<String, Object>) child, path, declared, blanked);
                continue;
            }
            if (value instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> childItem) {
                        blankSecretsIn((Map<String, Object>) childItem, path, declared, blanked);
                    }
                }
                continue;
            }
            if (isSecret(path, leaf, value, declared)) {
                entry.setValue("");
                blanked.add(path);
            }
        }
    }

    /** Whether one leaf value must not travel — see {@link #blankSecretsIn} for the four nets. */
    private static boolean isSecret(String path, String leaf, Object value, Set<String> declared) {
        if (declared.contains(path) || declared.contains(leaf)) {
            return true;
        }
        if (value instanceof String text && text.startsWith("ENC(")) {
            return true;
        }
        return looksSecret(leaf);
    }

    /**
     * Whether a key NAME is unambiguously a credential.
     *
     * <p>Separators and case are stripped so {@code secret-key}, {@code secretKey} and {@code SECRET_KEY} all
     * match. The word list is deliberately short and unambiguous; adding {@code key} would sweep up
     * {@code siteKey} (a reCAPTCHA site key is public by design — it is embedded in the page) and every
     * {@code sortKey}/{@code cacheKey} in existence.
     */
    private static boolean looksSecret(String leaf) {
        String name = leaf.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String word : SECRET_WORDS) {
            if (name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Key-name fragments that name a credential and essentially nothing else. */
    private static final List<String> SECRET_WORDS =
            List.of("secret", "password", "passwd", "token", "credential", "apikey", "privatekey");

    /** The {@code siteSettings} keys {@code pluginId} declares as {@link SettingType#SECRET}; empty if absent. */
    private Set<String> declaredSecretKeys(String pluginId) {
        if (runningPlugins == null) {
            return Set.of();
        }
        for (LoadedPlugin plugin : runningPlugins.installed()) {
            if (plugin.id().equals(pluginId)) {
                Set<String> keys = new java.util.LinkedHashSet<>();
                for (PluginSettingDef def : plugin.descriptor().siteSettings()) {
                    if (SettingType.SECRET.name().equalsIgnoreCase(def.type())) {
                        keys.add(def.key());
                    }
                }
                return keys;
            }
        }
        return Set.of();
    }

    /** Reads a flat YAML mapping, or {@code null} when it is unreadable or not a mapping (copy it through). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYamlMap(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed = Yaml.read(in, Map.class);
            return parsed instanceof Map ? new java.util.LinkedHashMap<>((Map<String, Object>) parsed) : null;
        } catch (IOException | RuntimeException unreadable) {
            log.warn("could not sanitise {} for transfer — copying it unchanged: {}", file, unreadable.toString());
            return null;
        }
    }

    private static byte[] writeYaml(Map<String, Object> value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Yaml.write(bytes, value);
        return bytes.toByteArray();
    }
}
