package mx.quest.runway.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A snapshot of a small, fixed set of files that answers one question cheaply and correctly: <b>is every file
 * still the version that was there when the snapshot was taken?</b> It is what lets an in-memory cache built
 * from files (the parsed-template cache) stay ON and still never outlive an edit made <b>on disk</b>, with no
 * console, no event and no reload involved.
 *
 * <h2>How a change is detected</h2>
 * Per file the snapshot keeps whether it existed, its size, its modification time at full precision and its
 * file key (device + inode on Unix, so a file swapped in by rename is a different file even with the old size
 * and timestamp). {@link #isCurrent()} re-reads those attributes — one {@code stat} per file, no read — and any
 * difference, including a file appearing or disappearing, means "changed".
 *
 * <h2>Why a content check as well — the "racily new" file</h2>
 * Size and timestamp alone are not enough for a file modified moments before the snapshot: a file system stamps
 * modification times in ticks (a few milliseconds on Linux, two seconds on FAT), so a second, same-size edit in
 * the same tick leaves every attribute identical. A file whose modification time lies within
 * {@link #RACY_WINDOW_MILLIS} of the snapshot is therefore treated as <i>racily new</i>: its content digest is
 * taken with the snapshot and compared on every check. Once a check runs more than that window after the newest
 * such modification time and still finds the content unchanged, the snapshot settles to attribute-only checks —
 * every later edit necessarily carries a later timestamp. (The same reasoning git applies to its index.)
 *
 * <h2>Directories</h2>
 * A path may also name a <b>directory</b>: its attributes change when an entry is created, deleted or renamed, so
 * it stands for "the set of files in it" (a bundle folder where a new locale file must be noticed). For a racily-new
 * directory the content compared is its sorted listing of entry names — never the entries' bytes.
 *
 * <p>Taken <em>before</em> the files are read for use, a snapshot can only err on the safe side: an edit landing
 * between the snapshot and the read makes the user hold newer content than the snapshot describes, and the next
 * check reports "changed" — one extra re-read, never a stale result.
 *
 * <p><b>Assumptions</b> (documented, not detectable): the file system's timestamp tick is at most
 * {@link #RACY_WINDOW_MILLIS}, its clock agrees with this JVM's within that window, and nobody rewrites a file in
 * place while forging back its exact previous size and nanosecond timestamp.
 *
 * <p>Thread-safe: immutable but for a one-way "settled" flag.
 */
public final class FileStamp {

    /** How recent a modification time must be for the file to count as racily new (above any FS tick). */
    public static final long RACY_WINDOW_MILLIS = 3_000L;

    /** A racily-new file larger than this is not digested; it reports "changed" until it settles. */
    static final long MAX_DIGEST_BYTES = 4L * 1024 * 1024;

    private final List<Entry> entries;
    private final LongSupplier clock;
    /** True while some file is racily new; cleared once a later check finds the content settled. */
    private volatile boolean racy;

    private FileStamp(List<Entry> entries, LongSupplier clock, boolean racy) {
        this.entries = entries;
        this.clock = clock;
        this.racy = racy;
    }

    /**
     * Snapshots {@code files} now. A {@code null} element is ignored; a missing file is recorded as absent (so its
     * later creation is a change).
     */
    public static FileStamp of(Path... files) {
        return of(System::currentTimeMillis, files);
    }

    /** As {@link #of(Path...)} with an explicit wall clock (milliseconds since the epoch) — for tests. */
    static FileStamp of(LongSupplier clock, Path... files) {
        Objects.requireNonNull(clock, "clock");
        long now = clock.getAsLong();
        boolean anyRacy = false;
        Entry[] taken = Arrays.stream(files).filter(Objects::nonNull)
                .map(file -> Entry.take(file, now)).toArray(Entry[]::new);
        for (Entry entry : taken) {
            anyRacy |= entry.racy;
        }
        return new FileStamp(List.of(taken), clock, anyRacy);
    }

    /**
     * Whether every file still matches the snapshot. Costs one {@code stat} per file; while a file is racily new
     * it also reads and digests that file.
     *
     * @return {@code false} as soon as any file was created, deleted, replaced, resized, re-timestamped or — while
     *         racily new — rewritten; {@code false} also when a file's attributes cannot be read
     */
    public boolean isCurrent() {
        long now = clock.getAsLong();
        for (Entry entry : entries) {
            if (!entry.attributesMatch()) {
                return false;
            }
        }
        if (!racy) {
            return true;
        }
        boolean stillRacy = false;
        for (Entry entry : entries) {
            if (!entry.racy) {
                continue;
            }
            if (!entry.contentMatches()) {
                return false;
            }
            stillRacy |= isRacy(entry.modified, now);
        }
        if (!stillRacy) {
            racy = false; // every racily-new file is now older than any tick: attributes suffice from here on
        }
        return true;
    }

    /** True while a racily-new file still forces content checks (exposed for tests). */
    boolean isRacy() {
        return racy;
    }

    private static boolean isRacy(FileTime modified, long now) {
        long mtime = modified.toMillis();
        return mtime > now - RACY_WINDOW_MILLIS; // also true for a timestamp in the future
    }

    /** One file's recorded state; {@code present == false} records an absent (or unreadable) file. */
    private record Entry(Path file, boolean present, long size, FileTime modified, Object fileKey,
                         boolean racy, byte[] digest) {

        static Entry take(Path file, long now) {
            BasicFileAttributes attributes = readAttributes(file);
            if (attributes == null) {
                return new Entry(file, false, -1, null, null, false, null);
            }
            boolean racy = isRacy(attributes.lastModifiedTime(), now);
            byte[] digest = racy ? digest(file, attributes.size()) : null;
            return new Entry(file, true, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey(),
                    racy, digest);
        }

        boolean attributesMatch() {
            BasicFileAttributes now = readAttributes(file);
            if (now == null) {
                return !present;
            }
            return present
                    && now.size() == size
                    && now.lastModifiedTime().equals(modified)
                    && Objects.equals(now.fileKey(), fileKey);
        }

        boolean contentMatches() {
            byte[] current = digest(file, size);
            return digest != null && current != null && MessageDigest.isEqual(digest, current);
        }

        /** The file's attributes, or {@code null} when it is absent or unreadable (treated as a change). */
        private static BasicFileAttributes readAttributes(Path file) {
            try {
                return Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException absent) {
                return null;
            } catch (IOException | SecurityException unreadable) {
                return null;
            }
        }

        /**
         * SHA-256 of the file — of a directory, of its sorted entry names — or {@code null} when it is too large to
         * digest or cannot be read.
         */
        private static byte[] digest(Path file, long size) {
            if (Files.isDirectory(file)) {
                return listingDigest(file);
            }
            if (size > MAX_DIGEST_BYTES) {
                return null;
            }
            try {
                return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            } catch (IOException | SecurityException unreadable) {
                return null;
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
            }
        }

        /** SHA-256 of a directory's entry names, sorted and {@code /}-separated (no name contains a {@code /}). */
        private static byte[] listingDigest(Path dir) {
            try (java.util.stream.Stream<Path> children = Files.list(dir)) {
                String listing = String.join("/", children.map(child -> child.getFileName().toString())
                        .sorted().toList());
                return MessageDigest.getInstance("SHA-256").digest(
                        listing.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException | SecurityException unreadable) {
                return null;
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
            }
        }
    }
}
