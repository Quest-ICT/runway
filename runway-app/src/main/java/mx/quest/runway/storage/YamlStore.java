package mx.quest.runway.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads and writes typed, immutable records as YAML files, with crash-safe atomic writes and
 * per-file serialization of concurrent in-process writers.
 *
 * <p>Single-JVM by design: a per-path {@link ReentrantLock} serializes concurrent writers (and
 * deletes) to the same file within this process; there is no multi-process writer support (that
 * is the database pattern, which is out of the kernel). Reads are lock-free — they always see a
 * complete file thanks to the atomic rename, never a partial write.
 */
public final class YamlStore {

    private final ConcurrentMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Reads the record stored in {@code file}, or {@link Optional#empty()} if the file does not
     * exist (an absent store is a normal "not configured yet" state, not an error).
     *
     * @throws IOException if the file exists but cannot be read
     */
    public <T> Optional<T> read(Path file, Class<T> type) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(type, "type");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Optional.ofNullable(Yaml.read(in, type));
        }
    }

    /**
     * Atomically writes {@code value} to {@code file}, serialized against other writers to the
     * same file in this process.
     *
     * @throws IOException if the write fails
     */
    public <T> void write(Path file, T value) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(value, "value");
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            AtomicFiles.write(file, out -> Yaml.write(out, value));
        } finally {
            lock.unlock();
        }
    }

    /** Whether a store file exists at {@code file}. */
    public boolean exists(Path file) {
        return Files.isRegularFile(Objects.requireNonNull(file, "file"));
    }

    /**
     * Deletes the store file if present, serialized against writers to the same file.
     *
     * @return {@code true} if a file was deleted
     * @throws IOException if the delete fails
     */
    public boolean delete(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            return Files.deleteIfExists(file);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(Path file) {
        return locks.computeIfAbsent(file.toAbsolutePath().normalize(), key -> new ReentrantLock());
    }
}
