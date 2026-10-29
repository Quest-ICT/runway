package mx.quest.runway.spi.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.FileEntry;
import mx.quest.runway.spi.service.PluginSiteFiles;

/**
 * A {@link PluginSiteFiles} over a temporary folder, for plugin unit tests, with the <b>kernel's confinement
 * rules</b>: the same lexical path rules, the same real-path check against symbolic links (inside the subtree and
 * for the subtree itself), a link at the leaf reading as absent, {@code deleteTree} never following links, the write
 * cap, and the 10,000-entry listing cap. A plugin whose tests pass against it behaves the same on the kernel.
 *
 * <pre>{@code
 * @TempDir Path sites;
 * PluginSiteFiles files = new TempDirSiteFiles(sites, "contact");   // sites/<siteId>/plugins/contact/...
 * Files.createDirectories(sites.resolve("acme"));                    // the site must exist to write, as on the kernel
 * }</pre>
 *
 * The layout matches the kernel's: {@code <sitesRoot>/<siteId>/plugins/<pluginId>/}. Thread-safe.
 */
public final class TempDirSiteFiles implements PluginSiteFiles {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    /** The kernel's listing cap. */
    public static final int MAX_LIST_ENTRIES = 10_000;
    /** The kernel's default write cap. */
    public static final long DEFAULT_MAX_WRITE_BYTES = 16L * 1024 * 1024;

    private final Path sitesRoot;
    private final String pluginId;
    private final long maxBytes;

    /** With the kernel's default 16 MiB cap. */
    public TempDirSiteFiles(Path sitesRoot, String pluginId) {
        this(sitesRoot, pluginId, DEFAULT_MAX_WRITE_BYTES);
    }

    public TempDirSiteFiles(Path sitesRoot, String pluginId, long maxBytes) {
        this.sitesRoot = Objects.requireNonNull(sitesRoot, "sitesRoot").toAbsolutePath().normalize();
        this.pluginId = requireSafe(pluginId, "plugin id");
        this.maxBytes = Math.min(maxBytes > 0 ? maxBytes : DEFAULT_MAX_WRITE_BYTES, Integer.MAX_VALUE - 16);
    }

    /** The subtree root for {@code siteId}: where this fake reads and writes. */
    public Path root(String siteId) {
        return sitesRoot.resolve(requireSafe(siteId, "site id")).resolve("plugins").resolve(pluginId);
    }

    @Override
    public Optional<String> readText(String siteId, String relPath) throws IOException {
        Path file = resolve(siteId, relPath, false);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        // As the host: a folder that cannot be searched throws; only a file certainly not there reads as empty.
        if (!attrs.isRegularFile()) {
            return Optional.empty();
        }
        if (attrs.size() > maxBytes) {
            throw new IOException("'" + relPath + "' is larger than the " + maxBytes + "-byte limit");
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("'" + relPath + "' grew past the limit while being read");
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Override
    public void writeText(String siteId, String relPath, String text) throws IOException {
        Objects.requireNonNull(text, "text");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("write of " + bytes.length + " bytes is over the " + maxBytes
                    + "-byte limit");
        }
        Path file = resolve(siteId, relPath, false);
        if (!Files.isDirectory(sitesRoot.resolve(siteId), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("site '" + siteId + "' does not exist");
        }
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("'" + relPath + "' is a folder, not a file");
        }
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public boolean delete(String siteId, String relPath) throws IOException {
        Path file = resolve(siteId, relPath, false);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(file);
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
        Optional<FileEntry> self = entryOf(dir); // a folder that cannot be searched throws, as in Runway
        if (self.isEmpty() || !self.get().directory()) {
            return List.of();
        }
        List<FileEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                entryOf(child).ifPresent(entries::add);
            }
        }
        entries.sort(Comparator.comparing(FileEntry::name));
        return List.copyOf(entries.size() > MAX_LIST_ENTRIES ? entries.subList(0, MAX_LIST_ENTRIES) : entries);
    }

    @Override
    public Optional<FileEntry> stat(String siteId, String relPath) throws IOException {
        return entryOf(resolve(siteId, relPath, false));
    }

    private static Optional<FileEntry> entryOf(Path path) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        if (attrs.isSymbolicLink() || !(attrs.isRegularFile() || attrs.isDirectory())) {
            return Optional.empty();
        }
        return Optional.of(new FileEntry(path.getFileName().toString(), attrs.isDirectory(), attrs.size(),
                attrs.lastModifiedTime().toInstant()));
    }

    private Path resolve(String siteId, String relPath, boolean allowRoot) {
        Path root = root(siteId);
        String problem = problemWith(relPath, allowRoot);
        if (problem != null) {
            throw new IllegalArgumentException("unsafe plugin file path '" + relPath + "': " + problem);
        }
        Path target = relPath.isEmpty() ? root : root.resolve(relPath).normalize();
        try {
            Path rootReal = real(root);
            if (!rootReal.equals(real(sitesRoot.resolve(siteId)).resolve("plugins").resolve(pluginId))) {
                throw new IllegalArgumentException("plugins/" + pluginId + " (or plugins/) is a symbolic link");
            }
            if (!real(target).startsWith(rootReal)) {
                throw new IllegalArgumentException("'" + relPath + "' leaves the plugin's folder through a link");
            }
        } catch (IOException unresolvable) {
            throw new IllegalArgumentException("could not verify that '" + relPath + "' stays inside", unresolvable);
        }
        return target;
    }

    private static Path real(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalized;
        }
        Path remainder = existing.relativize(normalized);
        Path realExisting = existing.toRealPath();
        return remainder.toString().isEmpty() ? realExisting : realExisting.resolve(remainder);
    }

    private static String problemWith(String relPath, boolean allowRoot) {
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
            return "contains a backslash";
        }
        for (int i = 0; i < relPath.length(); i++) {
            if (relPath.charAt(i) < 0x20 || relPath.charAt(i) == 0x7f) {
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

    private static String requireSafe(String id, String what) {
        if (id == null || id.equals(".") || id.equals("..") || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("unsafe " + what + ": " + id);
        }
        return id;
    }
}
