package mx.quest.runway.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.Zips;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The on-disk plugin lifecycle and the drift read model behind the operator's plugin-management screen. It is
 * the <b>authority for the next boot</b>: every mutation (install / enable / disable / uninstall) changes the
 * {@code external/plugins} tree, and {@link #list()} predicts what that tree would load by running the
 * <b>same</b> discovery + {@link PluginResolution} the loader uses at startup — so the "Blocked" badge can
 * never disagree with what would actually load. It is <b>not</b> a hot-reloader: nothing here touches the live
 * context (restart-to-install is the locked decision), so every change shows as drift until a restart.
 *
 * <p>The read model joins two views: the <b>frozen runtime</b> ({@link PluginManager#installed()} — what loaded
 * <em>this</em> boot) and a <b>fresh disk read</b> (what would load <em>next</em> boot). Their difference is the
 * drift that lights the "restart required" banner.
 *
 * <p>All mutations are kernel-owned and operate only under the plugins root resolved traversal-safely by
 * {@link StorageLayout#pluginDir(String)}; an id that is unsafe or would escape the root is rejected before any
 * filesystem touch. Authority to <em>call</em> these methods is enforced at the controller (a platform op
 * reserved to the application master/wildcard admin); this service assumes an already-authorized caller.
 */
@Service
public class PluginAdminService {

    private static final Logger log = LoggerFactory.getLogger(PluginAdminService.class);
    private static final String DESCRIPTOR = "plugin.yml";

    private final StorageLayout layout;
    private final RunningPlugins runningPlugins;
    /** Per-entry decompression cap for an uploaded plugin archive — bounds a zip bomb while comfortably fitting a
     *  bundled library jar (default 128 MiB). Operator-gated, so the default is generous; override if needed. */
    private final long maxEntryBytes;

    /** This boot's contained failures and refused contributions per plugin; {@code null} outside a context. */
    private final ContributionGuard guard;
    /** This boot's compliance warnings per plugin; {@code null} outside a context. */
    private final PluginCompliance compliance;

    /** The form without this boot's health data (unit tests). */
    public PluginAdminService(StorageLayout layout, RunningPlugins runningPlugins, long maxEntryBytes) {
        this(layout, runningPlugins, maxEntryBytes, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PluginAdminService(StorageLayout layout, RunningPlugins runningPlugins,
            @Value("${runway.plugins.max-entry-bytes:134217728}") long maxEntryBytes, ContributionGuard guard,
            PluginCompliance compliance) {
        this.layout = layout;
        this.runningPlugins = runningPlugins;
        this.maxEntryBytes = maxEntryBytes;
        this.guard = guard;
        this.compliance = compliance;
    }

    /**
     * What the next boot would load: the loader's own pre-resolution refusals (quarantine markers — read without
     * clearing stale ones, so this read model never changes the disk — and the linkage scanner's), the same
     * {@link PluginResolution} the loader runs, then the loader's before-refresh surface check over the loadable set
     * and, if it refused any, the resolution once more. One code path with the loader, so the badge cannot disagree.
     */
    private PluginResolution.Result resolveNextBoot(List<LoadedPlugin> discovered, Set<String> linksHost) {
        Map<String, String> preBlocked = new LinkedHashMap<>(
                PluginQuarantine.blocked(layout.external(), discovered, false));
        PluginContextInitializer.scanLinkage(discovered, PluginAdminService.class.getClassLoader(), preBlocked,
                linksHost, false);
        PluginResolution.Result resolution = PluginResolution.resolve(discovered, preBlocked);
        Map<String, String> surface = surfaceRefusals(resolution.loadable());
        if (surface.isEmpty()) {
            return resolution;
        }
        surface.forEach(preBlocked::putIfAbsent);
        return PluginResolution.resolve(discovered, preBlocked);
    }

    /** The last surface-check result, keyed by the fingerprint of every jar it loaded (the check loads classes). */
    private record SurfaceMemo(List<String> fingerprint, Map<String, String> refusals) {
    }

    private volatile SurfaceMemo surfaceMemo;

    /**
     * The before-refresh surface check over {@code loadable}, as the loader runs it — through a throwaway class loader
     * (closed afterwards) — remembered until any jar of the set changes, so viewing the plugins screen does not reload
     * plugin classes on every visit.
     */
    private Map<String, String> surfaceRefusals(List<LoadedPlugin> loadable) {
        List<String> fingerprint = PluginContextInitializer.jarFingerprint(loadable);
        SurfaceMemo memo = surfaceMemo;
        if (memo != null && memo.fingerprint().equals(fingerprint)) {
            return memo.refusals();
        }
        Map<String, String> refusals = PluginContextInitializer.surfaceRefusalsInThrowawayLoader(loadable,
                PluginAdminService.class.getClassLoader());
        surfaceMemo = new SurfaceMemo(fingerprint, Map.copyOf(refusals));
        return refusals;
    }

    // ---- read model -------------------------------------------------------------------------------------

    /**
     * Every installed plugin folder as a {@link PluginInstallation} row, id-sorted, joining the frozen runtime
     * (loaded this boot) with a fresh disk read + resolution (loadable / blocked next boot). Rows also include
     * any plugin that is running this boot but whose folder is gone (a pending uninstall), so the operator sees
     * it will unload on restart.
     */
    public List<PluginInstallation> list() {
        Path root = layout.plugins();
        Set<String> folders = PluginLoader.installedPluginIds(root);
        List<LoadedPlugin> discovered = PluginLoader.discover(root);          // enabled + valid, next-boot candidates
        Set<String> linkage = new java.util.HashSet<>();
        PluginResolution.Result resolution = resolveNextBoot(discovered, linkage);
        Set<String> loadableNext = ids(resolution.loadable());
        Map<String, String> blockedNext = resolution.blocked();
        Set<String> runningNow = ids(runningPlugins.installed());
        Map<String, List<String>> requiredBy = dependentsByTarget(discovered);

        Set<String> allIds = new TreeSet<>(folders);
        allIds.addAll(runningNow); // a removed-but-still-running plugin is a real row (pending uninstall)

        List<PluginInstallation> rows = new ArrayList<>(allIds.size());
        for (String id : allIds) {
            rows.add(row(root, id, folders.contains(id), runningNow.contains(id),
                    loadableNext.contains(id), blockedNext.get(id), requiredBy.getOrDefault(id, List.of()),
                    linkage.contains(id)));
        }
        return List.copyOf(rows);
    }

    private PluginInstallation row(Path root, String id, boolean present, boolean loaded,
                                   boolean loadableNext, String blockedReason, List<String> requiredBy,
                                   boolean linksHost) {
        Classification classification = present ? classify(root, id) : Classification.absentButRunning();
        boolean enabledOnDisk = classification.enabled();
        boolean wellFormed = classification.wellFormed();
        // The next-boot fate of this folder: it would be running iff it is in the loadable set.
        boolean drift = loadableNext != loaded;

        PluginStatus status;
        if (present && !wellFormed) {
            status = PluginStatus.MALFORMED;
        } else if (blockedReason != null) {
            status = PluginStatus.BLOCKED;
        } else if (present && !enabledOnDisk) {
            status = PluginStatus.DISABLED;
        } else if (loaded && !drift) {
            status = PluginStatus.LOADED;
        } else {
            status = PluginStatus.PENDING; // newly added / re-enabled / removed-while-running
        }
        boolean hasSettings = classification.descriptor() != null
                && !classification.descriptor().settings().isEmpty();
        ContributionGuard.PluginHealth health = loaded && guard != null ? guard.health(id)
                : ContributionGuard.PluginHealth.CLEAN;
        List<String> warnings = loaded && compliance != null ? compliance.warnings(id) : List.of();
        boolean quarantined = present && PluginQuarantine.read(layout.external(), id).isPresent()
                && blockedReason != null && blockedReason.startsWith("quarantined");
        return new PluginInstallation(id, classification.name(id), classification.version(), enabledOnDisk,
                wellFormed, loaded, present, blockedReason, classification.malformedReason(), requiredBy,
                status, drift, hasSettings, EssentialAllowlist.contains(id), health.failures(), health.lastError(),
                health.lastErrorAt(), health.degraded(), warnings, linksHost, quarantined);
    }

    /** Whether the next boot would load a different set of plugins than is running now (the drift banner). */
    public boolean restartRequired() {
        Path root = layout.plugins();
        Set<String> loadableNext = ids(resolveNextBoot(PluginLoader.discover(root), new java.util.HashSet<>())
                .loadable());
        Set<String> runningNow = ids(runningPlugins.installed());
        return !loadableNext.equals(runningNow);
    }

    /** The ids of enabled, valid plugins that {@code requires:} {@code id} — uninstalling it would block them. */
    public List<String> requiredBy(String id) {
        return dependentsByTarget(PluginLoader.discover(layout.plugins())).getOrDefault(id, List.of());
    }

    /**
     * Whether the plugin ships an <b>admin fragment</b> ({@code templates/admin.html}) — the optional
     * status/actions panel the console renders below the plugin's per-tenant settings form (fed by the plugin's
     * own beans via {@code __plugin__/<id>/admin}). Convention over a descriptor key; the id is traversal-safe.
     */
    public boolean hasAdminFragment(String id) {
        return Files.isRegularFile(layout.pluginDir(id).resolve("templates").resolve("admin.html"));
    }

    /**
     * Whether the plugin ships an <b>application-realm</b> admin fragment ({@code templates/admin-app.html}) — the
     * panel the <b>app-level</b> plugin settings page ({@code /__app/plugins/<id>/settings}) renders below the
     * generic form. This is a REALM-SCOPED variant of {@link #hasAdminFragment}: a plain {@code admin.html} is
     * tenant-oriented (it renders on the per-site settings page), so the app page must key off a distinct file —
     * otherwise a plugin's per-tenant panel would leak into the application console (and, for a security plugin like
     * {@code protection}, its app-level operator-login config would render on a tenant route). Convention over a
     * descriptor key; the id is traversal-safe.
     */
    public boolean hasAppAdminFragment(String id) {
        return Files.isRegularFile(layout.pluginDir(id).resolve("templates").resolve("admin-app.html"));
    }

    /**
     * The parsed descriptor of an installed plugin (well-formed or not), or empty when no such folder exists —
     * the source of the {@code settings:} schema the settings form renders. The id is resolved traversal-safely.
     */
    public java.util.Optional<PluginDescriptor> descriptor(String id) {
        Path folder = layout.pluginDir(id); // throws on an unsafe id
        Path descriptorFile = folder.resolve(DESCRIPTOR);
        if (!Files.isRegularFile(descriptorFile)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(parse(descriptorFile));
        } catch (RuntimeException unreadable) {
            return java.util.Optional.empty();
        }
    }

    // ---- mutations --------------------------------------------------------------------------------------

    /**
     * Installs (or upgrades) a plugin from an uploaded zip whose contents are the plugin folder — its
     * {@code plugin.yml} at the archive root or under a single wrapping directory, alongside the jar(s) and any
     * {@code lib/ templates/ static/ i18n/}. The descriptor is parsed and validated <b>before</b> anything is
     * committed; the content is then placed at {@code external/plugins/<id>/} (the id taken from the descriptor,
     * so the folder name always equals the id — the loader's hard rule), replacing a prior install of the same
     * id. The plugin does <b>not</b> load until the next restart.
     *
     * @return the validated descriptor of the installed plugin
     * <p>An archive whose id is an <b>essential</b> one ({@link EssentialAllowlist}) is refused before anything is
     * moved: the essential plugin's exemptions (no linkage scan or surface check, cannot be disabled or uninstalled,
     * its failure takes every tenant page to 503) are claimed by id, so the only copy the platform treats as essential
     * is the one the Runway installer/seed delivers.
     *
     * @throws IllegalArgumentException if the archive has no {@code plugin.yml}, the descriptor is invalid or names an
     *                                  essential plugin, or an entry path would escape the extraction root (zip-slip)
     */
    public PluginDescriptor install(InputStream zip) {
        Path staging = createTempDir("runway-plugin-install");
        try {
            extractZip(zip, staging);
            Path descriptorFile = findDescriptor(staging);
            if (descriptorFile == null) {
                throw new IllegalArgumentException("uploaded archive contains no " + DESCRIPTOR);
            }
            PluginDescriptor descriptor = parse(descriptorFile);
            descriptor.validate(); // throws with a precise reason on a bad id/version/requirement/realm
            if (EssentialAllowlist.contains(descriptor.id())) {
                // The essential exemptions (no linkage scan, no surface checks, cannot be disabled or uninstalled,
                // fail-closed 503 when it fails) are claimed by id alone; an upload must never acquire them.
                throw new IllegalArgumentException("plugin '" + descriptor.id() + "' is Runway's essential "
                        + "security-floor plugin; it is delivered with each Runway release by the installer, never "
                        + "uploaded from the console. To repair or replace it, copy seed/plugins/" + descriptor.id()
                        + "/ from the Runway home over external/plugins/" + descriptor.id() + "/ (or re-run the "
                        + "release's installer) and restart");
            }
            Path contentRoot = descriptorFile.getParent();
            Path target = layout.pluginDir(descriptor.id()); // traversal-safe; id already validated safe
            mkdirs(target.getParent());
            // Build the new version FULLY into a sibling before touching the existing one, so a cross-device
            // copy that fails leaves the prior install intact. The final swap is a same-filesystem rename (the
            // heavy copy already happened), so it cannot leave a half-written target.
            Path incoming = target.resolveSibling(descriptor.id() + ".installing");
            deleteRecursively(incoming); // clear any leftover from an aborted install
            moveTree(contentRoot, incoming);
            deleteRecursively(target);   // brief window: remove the old, then rename the new into place
            moveTree(incoming, target);
            // A new install is a different build: the plugin gets a fresh chance on the next boot.
            if (PluginQuarantine.clear(layout.external(), descriptor.id())) {
                log.info("cleared the quarantine marker of plugin '{}' (a new build was installed)", descriptor.id());
            }
            log.info("installed plugin '{}' v{} (applies on restart)", descriptor.id(), descriptor.version());
            return descriptor;
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Enables or disables a plugin by rewriting the single {@code enabled:} line in its {@code plugin.yml}
     * (preserving every other line and comment verbatim), appending the key if absent. Applies on restart.
     *
     * @throws IllegalArgumentException if the id is unsafe/escapes the root, no such plugin folder exists, or the
     *                                  plugin is an essential security-floor plugin being disabled
     */
    public void setEnabled(String id, boolean enabled) {
        if (!enabled && EssentialAllowlist.contains(id)) {
            throw new IllegalArgumentException("plugin '" + id + "' is an essential security-floor plugin and "
                    + "cannot be disabled — it provides the platform's abuse/brute-force protection");
        }
        Path descriptorFile = requireFolder(id).resolve(DESCRIPTOR);
        if (!Files.isRegularFile(descriptorFile)) {
            throw new IllegalArgumentException("plugin '" + id + "' has no " + DESCRIPTOR);
        }
        List<String> lines = readLines(descriptorFile);
        String replacement = "enabled: " + enabled;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            // Only a TOP-LEVEL enabled: (no leading indentation) — never a nested `enabled:` under, say, a
            // settings entry, which this would otherwise corrupt (it matched the first such line anywhere).
            if (lines.get(i).matches("(?i)enabled\\s*:.*")) {
                lines.set(i, replacement);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add(replacement);
        }
        writeLines(descriptorFile, lines);
        log.info("plugin '{}' {} (applies on restart)", id, enabled ? "enabled" : "disabled");
    }

    /**
     * <b>Retry</b> a quarantined plugin: deletes its quarantine marker, so the next boot tries to wire it again.
     * If it still breaks the startup, it is quarantined again (and the platform relaunches without it).
     *
     * @return whether a marker existed
     * @throws IllegalArgumentException if the id is unsafe
     */
    public boolean retry(String id) {
        if (!PathSafety.isSafeId(id)) {
            throw new IllegalArgumentException("unsafe plugin id: " + id);
        }
        boolean cleared = PluginQuarantine.clear(layout.external(), id);
        if (cleared) {
            log.info("cleared the quarantine marker of plugin '{}' — it will be tried again on the next boot", id);
        }
        return cleared;
    }

    /**
     * Removes a plugin's install folder entirely. App-level settings ({@code external/plugin-settings/<id>.yml})
     * and tenant-generated data live <b>outside</b> the install dir and are intentionally <b>not</b> touched, so
     * a later reinstall resumes with its configuration. Applies on restart.
     *
     * @throws IllegalArgumentException if the id is unsafe/escapes the root, no such plugin folder exists, or the
     *                                  plugin is an essential security-floor plugin
     */
    public void uninstall(String id) {
        if (EssentialAllowlist.contains(id)) {
            throw new IllegalArgumentException("plugin '" + id + "' is an essential security-floor plugin and "
                    + "cannot be uninstalled from the console");
        }
        Path folder = requireFolder(id);
        deleteRecursively(folder);
        log.info("uninstalled plugin '{}' (applies on restart)", id);
    }

    // ---- internals --------------------------------------------------------------------------------------

    /** The disk classification of one present folder: does it parse/validate, is it enabled, and its labels. */
    private record Classification(boolean wellFormed, boolean enabled, PluginDescriptor descriptor,
                                  String malformedReason) {
        static Classification absentButRunning() {
            // Running this boot but its folder is gone: it loaded, so treat it as well-formed + enabled for
            // labelling; the row is flagged via present=false / drift and shows as PENDING (uninstall).
            return new Classification(true, true, null, null);
        }

        String name(String id) {
            return descriptor != null && descriptor.name() != null && !descriptor.name().isBlank()
                    ? descriptor.name() : id;
        }

        String version() {
            return descriptor != null && descriptor.version() != null ? descriptor.version() : "?";
        }
    }

    private Classification classify(Path root, String id) {
        Path descriptorFile = root.resolve(id).resolve(DESCRIPTOR);
        if (!Files.isRegularFile(descriptorFile)) {
            return new Classification(false, false, null, "no " + DESCRIPTOR);
        }
        PluginDescriptor descriptor;
        try {
            descriptor = parse(descriptorFile);
        } catch (RuntimeException e) {
            return new Classification(false, false, null, "unreadable " + DESCRIPTOR + ": " + rootMessage(e));
        }
        try {
            descriptor.validate();
        } catch (RuntimeException e) {
            return new Classification(false, descriptor.isEnabled(), descriptor, rootMessage(e));
        }
        if (!descriptor.id().equals(id)) {
            return new Classification(false, descriptor.isEnabled(), descriptor,
                    "descriptor id '" + descriptor.id() + "' ≠ folder name '" + id + "'");
        }
        return new Classification(true, descriptor.isEnabled(), descriptor, null);
    }

    /** target id → ids of enabled, valid plugins whose {@code requires:} names it. */
    private static Map<String, List<String>> dependentsByTarget(List<LoadedPlugin> discovered) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (LoadedPlugin plugin : discovered) {
            for (PluginRequirement requirement : plugin.descriptor().requirements()) {
                out.computeIfAbsent(requirement.id(), k -> new ArrayList<>()).add(plugin.id());
            }
        }
        out.replaceAll((target, dependents) -> {
            dependents.sort(Comparator.naturalOrder());
            return List.copyOf(dependents);
        });
        return out;
    }

    private Path requireFolder(String id) {
        Path folder = layout.pluginDir(id); // throws on unsafe/escaping id
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("no plugin installed with id '" + id + "'");
        }
        return folder;
    }

    private static PluginDescriptor parse(Path descriptorFile) {
        try (InputStream in = Files.newInputStream(descriptorFile)) {
            return PluginDescriptor.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + descriptorFile, e);
        }
    }

    private static Set<String> ids(List<LoadedPlugin> plugins) {
        Set<String> ids = new java.util.HashSet<>();
        plugins.forEach(plugin -> ids.add(plugin.id()));
        return ids;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    // ---- filesystem helpers (zip-slip-safe) -------------------------------------------------------------

    private void extractZip(InputStream zip, Path destRoot) {
        Path normalizedRoot = destRoot.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(zip, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path resolved = normalizedRoot.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(normalizedRoot)) {
                    throw new IllegalArgumentException("archive entry escapes the extraction root: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    // Bounded per-entry copy — a plain Files.copy would let a decompression bomb fill the disk.
                    try (OutputStream out = Files.newOutputStream(resolved)) {
                        Zips.copyBounded(zin, out, maxEntryBytes);
                    }
                }
                zin.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("extracting uploaded plugin archive", e);
        }
    }

    /** Finds {@code plugin.yml} at the archive root or under a single wrapping directory; {@code null} if none. */
    private static Path findDescriptor(Path root) {
        Path atRoot = root.resolve(DESCRIPTOR);
        if (Files.isRegularFile(atRoot)) {
            return atRoot;
        }
        try (var entries = Files.list(root)) {
            for (Path child : entries.filter(Files::isDirectory).sorted().toList()) {
                Path candidate = child.resolve(DESCRIPTOR);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("scanning uploaded archive for " + DESCRIPTOR, e);
        }
        return null;
    }

    private static void moveTree(Path from, Path to) {
        try {
            Files.move(from, to);
        } catch (IOException atomicFailed) {
            copyTree(from, to); // cross-device or non-empty: fall back to a recursive copy
        }
    }

    private static void copyTree(Path from, Path to) {
        try (var walk = Files.walk(from)) {
            for (Path source : walk.sorted().toList()) {
                Path destination = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("copying plugin tree into place", e);
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("deleting " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("deleting " + path, e);
        }
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("creating " + dir, e);
        }
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException("creating a temp dir", e);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }

    private static void writeLines(Path file, List<String> lines) {
        try {
            AtomicFiles.write(file, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + file, e);
        }
    }

    /** Exposed only so the controller can echo back the traversal-safe resolved id in error messages. */
    String safeId(String id) {
        return PathSafety.requireSafeId(id);
    }
}
