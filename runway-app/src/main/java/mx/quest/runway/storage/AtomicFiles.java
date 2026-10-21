package mx.quest.runway.storage;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Crash-safe file writes: stream the new content into a temporary file in the same directory,
 * {@code fsync} it to disk, then atomically rename it over the target. A crash at any point
 * leaves either the old file intact or the fully-written new one — never a half-written store.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** Writes content to a stream that the caller fills. The stream is managed (and synced) here. */
    @FunctionalInterface
    public interface ContentWriter {
        /** Writes the full content to {@code out}. Must not close {@code out}. */
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Atomically writes {@code target} with the bytes produced by {@code content}. Creates parent
     * directories as needed. On any failure the temporary file is removed and the original target
     * is left untouched.
     *
     * @throws IOException if writing, syncing, or renaming fails
     */
    public static void write(Path target, ContentWriter content) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        Path absolute = target.toAbsolutePath().normalize();
        Path dir = absolute.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + absolute.getFileName() + ".", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                content.writeTo(out);
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(tmp);
            throw failure;
        }
    }

    /** Atomically writes {@code target} with the given bytes. */
    public static void write(Path target, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        write(target, out -> out.write(bytes));
    }
}
