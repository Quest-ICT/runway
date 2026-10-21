package mx.quest.runway.plugin.spi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.spi.model.FileEntry;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The kernel's {@link PluginSiteFiles} for <b>one</b> plugin: its subtree {@code sites/<siteId>/plugins/<pluginId>/}.
 * Built once per plugin by {@code PluginScopedServices} and bound to the plugin by code source, so the plugin id is
 * never an argument.
 *
 * <h2>The confinement, in three layers</h2>
 * <ol>
 *   <li><b>Lexical.</b> The site id must be a {@linkplain PathSafety#isSafeId safe id}. A relative path must use
 *       {@code /}, and may not be absolute, contain a backslash or a control character, or have an empty,
 *       {@code .} or {@code ..} segment. Checked before any I/O.</li>
 *   <li><b>The subtree itself is real.</b> The real path of {@code plugins/<pluginId>} must be exactly the site
 *       folder's real path plus {@code plugins/<pluginId>}: a symbolic link standing in for {@code plugins/} or for
 *       the plugin's folder (which would make the whole subtree another site's folder, or the site folder itself)
 *       is refused. Without this, a link {@code plugins/contact -> ..} would let a write land beside
 *       {@code users.yml}.</li>
 *   <li><b>The target stays inside.</b> The real path of the target (its deepest existing ancestor, resolved) must
 *       lie under the subtree's real path, so a symbolic link to a directory planted anywhere inside cannot carry
 *       the operation out. The leaf is opened with {@link LinkOption#NOFOLLOW_LINKS}, a link at the leaf reads as
 *       absent, and {@link #deleteTree} removes links as links without following them.</li>
 * </ol>
 * Plugins are trusted code (this is not a sandbox), so the remaining window — something other than the plugin
 * swapping a directory for a link between the check and the write — is accepted; the checks exist so that a link
 * planted in tenant data (by a restored archive, by hand) can never redirect a plugin's I/O.
 *
 * <p>Writes go through {@link AtomicFiles} (temporary sibling, {@code fsync}, rename). A write to a site whose folder
 * does not exist fails rather than creating it, so a late write for a deleted site cannot resurrect a stray folder
 * that a new site with the same id would inherit.
 *
 * <p>Thread-safe (stateless apart from configuration).
 */
public final class PluginSiteFilesImpl implements PluginSiteFiles {

    private static final Logger log = LoggerFactory.getLogger(PluginSiteFilesImpl.class);

    /** The most entries {@link #list} returns. */
    public static final int MAX_LIST_ENTRIES = 10_000;

    /** The default for {@code runway.plugins.site-files.max-write-bytes}: 16 MiB. */
    public static final long DEFAULT_MAX_WRITE_BYTES = 16L * 1024 * 1024;

    private static final String PLUGINS_DIR = "plugins";

    private final String pluginId;
    private final StorageLayout layout;
    private final long maxBytes;

    /**
     * @param pluginId the plugin this instance is bound to (a safe id)
     * @param layout   the storage layout
     * @param maxBytes the per-write (and per-read) cap; a non-positive value means the default
     */
    public PluginSiteFilesImpl(String pluginId, StorageLayout layout, long maxBytes) {
        this.pluginId = PathSafety.requireSafeId(pluginId);
        this.layout = Objects.requireNonNull(layout, "layout");
        // A whole-file read must fit one array, so the cap never exceeds what an array can hold.
        long cap = maxBytes > 0 ? maxBytes : DEFAULT_MAX_WRITE_BYTES;
        this.maxBytes = Math.min(cap, Integer.MAX_VALUE - 16);
    }

    /** The plugin this instance is bound to. */
    public String pluginId() {
        return pluginId;
    }

    @Override
    public Optional<String> readText(String siteId, String relPath) throws IOException {
        Path file = resolve(siteId, relPath, false);
        if (!isRegularFile(file)) {
            return Optional.empty();
        }
        if (Files.size(file) > maxBytes) {
            throw new IOException("plugin '" + pluginId + "' file '" + relPath + "' of site '" + siteId
                    + "' is larger than the " + maxBytes + "-byte limit (runway.plugins.site-files.max-write-bytes)");
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes((int) maxBytes + 1); // bounded even if the file grew since the size check
        }
        if (bytes.length > maxBytes) {
            throw new IOException("plugin '" + pluginId + "' file '" + relPath + "' of site '" + siteId
                    + "' grew past the " + maxBytes + "-byte limit while being read");
        }
        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public void writeText(String siteId, String relPath, String text) throws IOException {
        Objects.requireNonNull(text, "text");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("plugin '" + pluginId + "' write of " + bytes.length + " bytes to '"
                    + relPath + "' is over the " + maxBytes + "-byte limit (runway.plugins.site-files.max-write-bytes)");
        }
        Path file = resolve(siteId, relPath, false);
        if (!Files.isDirectory(layout.siteDir(siteId), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("site '" + siteId + "' does not exist; plugin '" + pluginId + "' cannot write '"
                    + relPath + "' for it");
        }
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("'" + relPath + "' is a folder, not a file");
        }
        AtomicFiles.write(file, bytes);
    }

    @Override
    public boolean delete(String siteId, String relPath) throws IOException {
        Path file = resolve(siteId, relPath, false);
        if (!isRegularFile(file)) {
            return false;
        }
        return Files.deleteIfExists(file);
    }

    @Override
    public boolean deleteTree(String siteId, String relDir) throws IOException {
        Path target = resolve(siteId, relDir, false);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            return false;
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return Files.deleteIfExists(target);
        }
        // walkFileTree without FOLLOW_LINKS reports a link as a file (visitFile) and never descends into it, so a
        // link to anywhere else is deleted as a link and its target is untouched.
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }

    @Override
    public List<FileEntry> list(String siteId, String relDir) throws IOException {
        Path dir = resolve(siteId, relDir, true);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<FileEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                entryOf(child).ifPresent(entries::add);
            }
        }
        entries.sort(Comparator.comparing(FileEntry::name));
        if (entries.size() > MAX_LIST_ENTRIES) {
            log.warn("plugin '{}' listed '{}' of site '{}': {} entries, only the first {} by name are returned",
                    pluginId, relDir, siteId, entries.size(), MAX_LIST_ENTRIES);
            return List.copyOf(entries.subList(0, MAX_LIST_ENTRIES));
        }
        return List.copyOf(entries);
    }

    @Override
    public Optional<FileEntry> stat(String siteId, String relPath) throws IOException {
        return entryOf(resolve(siteId, relPath, false));
    }

    // --- internals ----------------------------------------------------------------------------------------------

    /** A regular file or a folder, never a link (nor a device, socket, …); empty for anything else or nothing. */
    private static Optional<FileEntry> entryOf(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || !(attrs.isRegularFile() || attrs.isDirectory())) {
            return Optional.empty();
        }
        return Optional.of(new FileEntry(path.getFileName().toString(), attrs.isDirectory(), attrs.size(),
                attrs.lastModifiedTime().toInstant()));
    }

    private static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Resolves {@code relPath} under the plugin's subtree of {@code siteId}, applying all three confinement layers
     * (see the class comment).
     *
     * @param allowRoot whether {@code ""} (the subtree root itself) is accepted
     * @throws IllegalArgumentException on any violation
     */
    Path resolve(String siteId, String relPath, boolean allowRoot) {
        if (siteId == null || !PathSafety.isSafeId(siteId)) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
        String problem = problemWith(relPath, allowRoot);
        if (problem != null) {
            throw new IllegalArgumentException("unsafe plugin file path '" + relPath + "': " + problem);
        }
        Path siteDir = layout.siteDir(siteId);
        Path root = siteDir.resolve(PLUGINS_DIR).resolve(pluginId).toAbsolutePath().normalize();
        Path target = relPath.isEmpty() ? root : root.resolve(relPath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("plugin file path escapes its folder: " + relPath); // unreachable
        }
        try {
            Path rootReal = PathSafety.effectiveRealPath(root);
            Path expected = PathSafety.effectiveRealPath(siteDir).resolve(PLUGINS_DIR).resolve(pluginId);
            if (!rootReal.equals(expected)) {
                throw new IllegalArgumentException("the folder plugins/" + pluginId + " of site '" + siteId
                        + "' (or plugins/ itself) is a symbolic link; refusing to follow it");
            }
            if (!PathSafety.effectiveRealPath(target).startsWith(rootReal)) {
                throw new IllegalArgumentException("plugin file path '" + relPath
                        + "' leaves the plugin's folder through a symbolic link");
            }
        } catch (IOException unresolvable) {
            throw new IllegalArgumentException("could not verify that '" + relPath + "' stays inside plugins/"
                    + pluginId + " of site '" + siteId + "' (" + unresolvable.getClass().getSimpleName() + ")",
                    unresolvable);
        }
        return target;
    }

    /** Why {@code relPath} breaks the path rules, or {@code null}. */
    static String problemWith(String relPath, boolean allowRoot) {
        if (relPath == null) {
            return "null";
        }
        if (relPath.isEmpty()) {
            return allowRoot ? null : "empty";
        }
        if (relPath.startsWith("/")) {
            return "absolute";
        }
        if (relPath.indexOf('\\') >= 0) {
            return "contains a backslash (use /)";
        }
        for (int i = 0; i < relPath.length(); i++) {
            char c = relPath.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return "contains a control character";
            }
        }
        for (String segment : relPath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "has an empty, '.' or '..' segment";
            }
        }
        return null;
    }
}
