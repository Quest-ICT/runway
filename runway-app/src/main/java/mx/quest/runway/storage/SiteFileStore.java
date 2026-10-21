package mx.quest.runway.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The per-site file primitive: reads, writes, lists and deletes ordinary files that live inside one
 * site's folder ({@code sites/<id>/…}). It is the single choke point through which the icon, document
 * and stylesheet screens touch a tenant's own asset files, so the tenant-isolation and crash-safety
 * guarantees live here once instead of in every caller.
 *
 * <h2>Isolation</h2>
 * Every operation is confined to the addressed site's folder. The {@code siteId} is resolved through
 * {@link StorageLayout#siteDir(String)} (a strict {@linkplain PathSafety#requireSafeId safe id}, so it
 * can never select another site), and the {@code relPath} is resolved with
 * {@link PathSafety#resolveConfined} (rich names and {@code /} sub-paths are allowed, but any attempt to
 * escape the site folder via {@code ..} or an absolute path is rejected). Lexical confinement is not
 * enough on its own, because a <em>symlinked directory component</em> planted inside the folder would
 * still pass a string {@code startsWith} check while pointing elsewhere — so every resolution also
 * re-checks the <em>real</em> (symlink-resolved) path against the site folder's real path, closing that
 * escape on both the read and the write edge.
 *
 * <p><b>No symbolic link inside the site folder is followed, on any edge.</b> A path whose existing part goes
 * through a symbolic link below {@code sites/<id>/} is refused, wherever the link points. Real-path confinement
 * alone kept a link inside the site: a link {@code static/pg -> ../pages} planted by hand let the Static files
 * screen delete a page and upload into {@code pages/}, and {@code static/d2 -> doc} got past the Documents guard,
 * because a caller's subtree check ({@code static/}, not {@code static/doc/}) reads only the path as written. With
 * no link followed, the path a caller checked is the path the I/O reaches, so every caller's subtree rule holds.
 * Nothing in Runway creates a link inside a site folder; one put there by hand is simply not used. (A link
 * <em>above</em> the site folder, such as a symlinked storage root, is not affected.)
 *
 * <p>The site folder — not a per-subtree grammar — is the boundary this class enforces; a caller that must
 * additionally stay within a subtree (e.g. documents confined to {@code static/doc/}) enforces that narrower scope
 * itself before calling in, and can rely on the path meaning what it says.
 *
 * <h2>Crash safety</h2>
 * Writes go through {@link AtomicFiles}: the bytes are streamed to a temporary sibling, {@code fsync}ed,
 * then atomically renamed over the target, so a crash leaves either the old file or the whole new one.
 *
 * <h2>Size</h2>
 * Each write carries the caller's own {@code maxBytes} ceiling (icons are tiny, documents large), checked
 * as a defence-in-depth net — callers that receive an upload should still reject an over-size file up front
 * from its declared length rather than buffering it only to have this throw.
 */
@Component
public final class SiteFileStore {

    private final StorageLayout layout;

    public SiteFileStore(StorageLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    /**
     * Atomically writes {@code bytes} to {@code sites/<id>/<relPath>}, creating parent folders as needed.
     *
     * @throws IllegalArgumentException if {@code siteId}/{@code relPath} is unsafe, or {@code bytes}
     *                                  exceeds {@code maxBytes}
     * @throws IOException              if the write fails
     */
    public void write(String siteId, String relPath, byte[] bytes, long maxBytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "file is " + bytes.length + " bytes, over the " + maxBytes + "-byte limit");
        }
        AtomicFiles.write(resolve(siteId, relPath), bytes);
    }

    /** Atomically writes {@code text} (UTF-8) to {@code sites/<id>/<relPath>}. */
    public void writeText(String siteId, String relPath, String text, long maxBytes) throws IOException {
        Objects.requireNonNull(text, "text");
        write(siteId, relPath, text.getBytes(StandardCharsets.UTF_8), maxBytes);
    }

    /**
     * Reads {@code sites/<id>/<relPath>}. Returns empty if the path is unsafe, absent, a directory, or a
     * symbolic link — never throws for those (a caller serving a public asset wants a clean 404, not a 500).
     */
    public Optional<byte[]> read(String siteId, String relPath) {
        Path file = resolveQuietly(siteId, relPath);
        if (file == null || !isServableFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Reads {@code sites/<id>/<relPath>} as UTF-8 text, or empty (see {@link #read}). */
    public Optional<String> readText(String siteId, String relPath) {
        return read(siteId, relPath).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * The confined, symlink-safe filesystem {@link Path} of a servable regular file — for <b>streaming</b> a
     * response without buffering the whole file into memory. Empty when the path is unsafe, absent, a
     * directory, or a symlink. The returned path has already passed the same site-confinement and real-path
     * symlink checks as every other operation; a caller may hand it to a streaming resource but must not
     * treat it as licence to escape the store's guarantees.
     */
    public Optional<Path> resolveServable(String siteId, String relPath) {
        Path file = resolveQuietly(siteId, relPath);
        return (file != null && isServableFile(file)) ? Optional.of(file) : Optional.empty();
    }

    /** Whether {@code sites/<id>/<relPath>} is a present, non-symlink regular file. */
    public boolean exists(String siteId, String relPath) {
        Path file = resolveQuietly(siteId, relPath);
        return file != null && isServableFile(file);
    }

    /** The size in bytes of {@code sites/<id>/<relPath>}, or {@code -1} if it is not a servable file. */
    public long size(String siteId, String relPath) {
        Path file = resolveQuietly(siteId, relPath);
        if (file == null || !isServableFile(file)) {
            return -1;
        }
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Deletes {@code sites/<id>/<relPath>} if present.
     *
     * @return {@code true} if a file was deleted, {@code false} if it was absent or the path is unsafe
     * @throws IOException if the deletion fails
     */
    public boolean delete(String siteId, String relPath) throws IOException {
        Path file = resolveQuietly(siteId, relPath);
        return file != null && Files.deleteIfExists(file);
    }

    /**
     * Lists the regular files under {@code sites/<id>/<subtree>}, as paths relative to that subtree, sorted.
     * Symbolic links and directories are omitted. An absent or non-directory subtree (or an unsafe one)
     * yields an empty list rather than an error.
     */
    public List<String> list(String siteId, String subtree) throws IOException {
        Path root = resolveQuietly(siteId, subtree);
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(SiteFileStore::isServableFile)
                    .map(path -> root.relativize(path).toString())
                    .sorted()
                    .toList();
        }
    }

    /**
     * One entry of a folder listing: a sub-folder or a regular file.
     *
     * @param name      the entry's own name
     * @param directory whether it is a folder
     * @param size      the file's size in bytes ({@code 0} for a folder)
     * @param modified  last-modified time in epoch millis ({@code 0} if unreadable)
     */
    public record Entry(String name, boolean directory, long size, long modified) {
    }

    /**
     * The direct children of the folder {@code sites/<id>/<relDir>}: folders first, then files, each by name.
     * Symbolic links are omitted, like everything this store exposes. An absent folder or an unsafe path is an
     * empty list.
     */
    public List<Entry> listDirectory(String siteId, String relDir) throws IOException {
        Path dir = resolveQuietly(siteId, relDir);
        if (dir == null || !Files.isDirectory(dir) || Files.isSymbolicLink(dir)) {
            return List.of();
        }
        List<Entry> out = new java.util.ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                boolean folder = Files.isDirectory(child);
                if (!folder && !Files.isRegularFile(child)) {
                    continue;
                }
                long size = 0;
                long modified = 0;
                try {
                    size = folder ? 0 : Files.size(child);
                    modified = Files.getLastModifiedTime(child).toMillis();
                } catch (IOException ignored) {
                    // a child that vanished between listing and stat is shown with zeros
                }
                out.add(new Entry(child.getFileName().toString(), folder, size, modified));
            }
        }
        out.sort(java.util.Comparator.comparing((Entry e) -> !e.directory()).thenComparing(Entry::name));
        return out;
    }

    /** Whether {@code sites/<id>/<relPath>} is a folder (not a symbolic link). */
    public boolean isDirectory(String siteId, String relPath) {
        Path dir = resolveQuietly(siteId, relPath);
        return dir != null && Files.isDirectory(dir) && !Files.isSymbolicLink(dir);
    }

    /**
     * Creates the folder {@code sites/<id>/<relDir>} and any missing parents, confined like a write.
     *
     * @throws IllegalArgumentException for an unsafe path
     */
    public void createDirectory(String siteId, String relDir) throws IOException {
        Files.createDirectories(resolve(siteId, relDir));
    }

    /**
     * Deletes the folder {@code sites/<id>/<relDir>} when it is empty.
     *
     * @return {@code false} when it is absent, unsafe, or not empty (nothing is deleted then)
     */
    public boolean deleteEmptyDirectory(String siteId, String relDir) throws IOException {
        Path dir = resolveQuietly(siteId, relDir);
        if (dir == null || !Files.isDirectory(dir) || Files.isSymbolicLink(dir)) {
            return false;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isPresent()) {
                return false;
            }
        }
        return Files.deleteIfExists(dir);
    }

    /**
     * Resolves strictly, throwing on an unsafe id/path — for write/mutate edges that must fail loudly.
     *
     * <p>The link check is check-then-act: a folder link created between this check and the write would be followed.
     * Only an account that can already write the site folder (the Runway user, or root) can create one, and that
     * account owns every file here anyway; Runway itself never creates links (restore unpacks with
     * {@code java.util.zip}, which writes plain files). So the check closes links placed earlier, by hand or by a
     * copy, not a racing local attacker, who is out of scope.
     */
    private Path resolve(String siteId, String relPath) {
        Path siteDir = layout.siteDir(siteId);
        Path resolved = PathSafety.resolveConfined(siteDir, relPath);
        requireNoLinkInside(siteDir, resolved);
        requireRealContainment(siteDir, resolved);
        return resolved;
    }

    /**
     * Refuses a path that goes through a symbolic link below the site folder (see the class notes): each existing
     * component from the site folder down to {@code resolved}, the leaf included, must be a real file or folder. The
     * walk stops at the first component that does not exist yet (a write's new folders and file).
     */
    private static void requireNoLinkInside(Path siteDir, Path resolved) {
        Path base = siteDir.toAbsolutePath().normalize();
        Path current = base;
        for (Path part : base.relativize(resolved.toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("path goes through a symbolic link inside the site folder: "
                        + base.relativize(current));
            }
            if (!Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return; // nothing below this exists yet, so nothing below it can be a link
            }
        }
    }

    /** Resolves for a read edge, returning {@code null} rather than throwing on an unsafe id/path. */
    private Path resolveQuietly(String siteId, String relPath) {
        try {
            return resolve(siteId, relPath);
        } catch (IllegalArgumentException unsafe) {
            return null;
        }
    }

    /**
     * Defence beyond lexical confinement: verify the <em>real</em> path (all symlinks resolved) still sits
     * inside the site folder's real path. A symlinked directory planted inside the folder passes
     * {@code resolveConfined}'s string check but redirects the actual I/O elsewhere; this catches it before
     * a read serves, or a write lands in, another tenant's folder. If confinement cannot be verified we
     * refuse rather than risk an escape.
     */
    private static void requireRealContainment(Path siteDir, Path resolved) {
        try {
            if (!effectiveReal(resolved).startsWith(effectiveReal(siteDir))) {
                throw new IllegalArgumentException("path escapes its site folder via a symlink: " + resolved);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("could not verify path confinement: " + resolved, e);
        }
    }

    /** The real path of {@code path}'s deepest existing ancestor plus the remainder: {@link PathSafety#effectiveRealPath}. */
    private static Path effectiveReal(Path path) throws IOException {
        return PathSafety.effectiveRealPath(path);
    }

    /** A file we will serve/expose: a real regular file, never a symlink (which could point out of folder). */
    private static boolean isServableFile(Path file) {
        return Files.isRegularFile(file) && !Files.isSymbolicLink(file);
    }
}
