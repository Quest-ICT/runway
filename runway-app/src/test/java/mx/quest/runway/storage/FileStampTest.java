package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FileStamp}: every way a file can change is a change; an untouched file is not; racily-new files. */
class FileStampTest {

    private static final long HOUR = 3_600_000L;

    /** A wall clock the test moves by hand. */
    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

    private static Path file(Path dir, String name, String content, long mtimeMillis) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtimeMillis));
        return f;
    }

    private FileStamp stamp(Path... files) {
        return FileStamp.of(now::get, files);
    }

    @Test
    void anUntouchedSettledFileStaysCurrentWithoutBeingRead(@TempDir Path dir) throws IOException {
        Path f = file(dir, "a.html", "one", now.get() - HOUR);
        FileStamp stamp = stamp(f);

        assertFalse(stamp.isRacy(), "an hour-old file is settled: attributes suffice");
        assertTrue(stamp.isCurrent());
        assertTrue(stamp.isCurrent(), "and stays current");
    }

    @Test
    void aContentEditIsAChange(@TempDir Path dir) throws IOException {
        Path f = file(dir, "a.html", "one", now.get() - HOUR);
        FileStamp stamp = stamp(f);

        Files.writeString(f, "two-longer");

        assertFalse(stamp.isCurrent());
    }

    @Test
    void aSameSizeEditWithANewTimestampIsAChange(@TempDir Path dir) throws IOException {
        Path f = file(dir, "a.html", "one", now.get() - HOUR);
        FileStamp stamp = stamp(f);

        file(dir, "a.html", "two", now.get() - HOUR + 1); // one millisecond later, same size

        assertFalse(stamp.isCurrent());
    }

    @Test
    void aTimestampOnlyChangeIsAChange(@TempDir Path dir) throws IOException {
        Path f = file(dir, "a.html", "one", now.get() - HOUR);
        FileStamp stamp = stamp(f);

        Files.setLastModifiedTime(f, FileTime.fromMillis(now.get() - HOUR - 1)); // even backwards (cp -p, rsync -t)

        assertFalse(stamp.isCurrent(), "conservative: a touched file is re-read");
    }

    @Test
    void aFileReplacedByRenameWithTheSameSizeAndTimestampIsAChange(@TempDir Path dir) throws IOException {
        long old = now.get() - HOUR;
        Path f = file(dir, "a.html", "one", old);
        FileStamp stamp = stamp(f);

        Path staged = file(dir, "a.html.new", "two", old);
        Files.move(staged, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        assertFalse(stamp.isCurrent(), "a new inode is a new file");
    }

    @Test
    void aDeletedFileIsAChange(@TempDir Path dir) throws IOException {
        Path f = file(dir, "a.html", "one", now.get() - HOUR);
        FileStamp stamp = stamp(f);

        Files.delete(f);

        assertFalse(stamp.isCurrent());
    }

    @Test
    void anAbsentFileStaysCurrentUntilItAppears(@TempDir Path dir) throws IOException {
        Path f = file(dir, "a.html", "one", now.get() - HOUR);
        Path logic = dir.resolve("a.th.xml");
        FileStamp stamp = stamp(f, logic, null);

        assertTrue(stamp.isCurrent(), "absent and still absent; a null path is ignored");

        file(dir, "a.th.xml", "<thlogic/>", now.get() - HOUR);

        assertFalse(stamp.isCurrent(), "a file that appears is a change");
    }

    @Test
    void anyOneOfSeveralFilesChangingIsAChange(@TempDir Path dir) throws IOException {
        Path html = file(dir, "a.html", "one", now.get() - HOUR);
        Path logic = file(dir, "a.th.xml", "<thlogic/>", now.get() - HOUR);
        FileStamp stamp = stamp(html, logic);

        Files.writeString(logic, "<thlogic></thlogic>");

        assertFalse(stamp.isCurrent());
    }

    /** The case attributes cannot see: the same size and the same timestamp tick. The content check sees it. */
    @Test
    void aRacilyNewFileRewrittenWithinTheSameTickIsAChange(@TempDir Path dir) throws IOException {
        long tick = now.get() - 100; // modified 100 ms before the snapshot
        Path f = file(dir, "a.html", "one", tick);
        FileStamp stamp = stamp(f);
        assertTrue(stamp.isRacy());
        assertTrue(stamp.isCurrent(), "unchanged content is current");

        file(dir, "a.html", "two", tick); // same size, same timestamp

        assertFalse(stamp.isCurrent());
    }

    @Test
    void aFutureTimestampIsTreatedAsRacilyNew(@TempDir Path dir) throws IOException {
        long future = now.get() + HOUR;
        Path f = file(dir, "a.html", "one", future);
        FileStamp stamp = stamp(f);
        assertTrue(stamp.isRacy(), "a clock-skewed future time proves nothing about later edits");

        file(dir, "a.html", "two", future);

        assertFalse(stamp.isCurrent());
    }

    @Test
    void aRacilyNewFileSettlesOnceTheWindowHasPassed(@TempDir Path dir) throws IOException {
        long tick = now.get() - 100;
        Path f = file(dir, "a.html", "one", tick);
        FileStamp stamp = stamp(f);

        assertTrue(stamp.isCurrent());
        assertTrue(stamp.isRacy(), "still within the window: keeps checking content");

        now.addAndGet(FileStamp.RACY_WINDOW_MILLIS + 1);
        assertTrue(stamp.isCurrent());
        assertFalse(stamp.isRacy(), "past the window with the content unchanged: settled");

        // From here an edit can only carry a later timestamp, which the attribute check sees.
        file(dir, "a.html", "two", now.get());
        assertFalse(stamp.isCurrent());
    }

    @Test
    void aRacilyNewFileRewrittenBeforeItSettlesNeverSettlesOnTheNewContent(@TempDir Path dir) throws IOException {
        long tick = now.get() - 100;
        Path f = file(dir, "a.html", "one", tick);
        FileStamp stamp = stamp(f);
        file(dir, "a.html", "two", tick);

        now.addAndGet(FileStamp.RACY_WINDOW_MILLIS + 1);

        assertFalse(stamp.isCurrent(), "the content check runs before settling");
        assertTrue(stamp.isRacy(), "a failed check does not settle the stamp");
    }

    @Test
    void aRacilyNewFileTooLargeToDigestReportsAChangeUntilRestamped(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("big.html");
        Files.write(f, new byte[(int) FileStamp.MAX_DIGEST_BYTES + 1]);
        Files.setLastModifiedTime(f, FileTime.fromMillis(now.get() - 100));

        assertFalse(stamp(f).isCurrent(), "unverifiable while racily new: re-read rather than trusted");

        Files.setLastModifiedTime(f, FileTime.fromMillis(now.get() - HOUR));
        assertTrue(stamp(f).isCurrent(), "a settled large file is checked by attributes alone");
    }

    // ---- directories (a bundle folder where a new locale file must be noticed) -----------------------------------

    @Test
    void aFileAppearingInASettledDirectoryIsAChange(@TempDir Path dir) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("i18n"));
        Files.setLastModifiedTime(folder, FileTime.fromMillis(now.get() - HOUR));
        FileStamp stamp = stamp(folder);
        assertFalse(stamp.isRacy());
        assertTrue(stamp.isCurrent(), "an untouched folder is current");

        Files.writeString(folder.resolve("messages_fr.properties"), "a=b\n");

        assertFalse(stamp.isCurrent(), "creating an entry changes the folder");
    }

    /** A racily-new folder compares its listing, not bytes it does not have — so an untouched one stays current. */
    @Test
    void aRacilyNewDirectoryIsComparedByItsListing(@TempDir Path dir) throws IOException {
        long tick = now.get() - 100;
        Path folder = Files.createDirectories(dir.resolve("i18n"));
        Files.writeString(folder.resolve("messages.properties"), "a=b\n");
        Files.setLastModifiedTime(folder, FileTime.fromMillis(tick));
        FileStamp stamp = stamp(folder);
        assertTrue(stamp.isRacy());
        assertTrue(stamp.isCurrent(), "an unchanged racily-new folder is current (not re-read forever)");

        Files.writeString(folder.resolve("messages_es.properties"), "a=c\n");
        Files.setLastModifiedTime(folder, FileTime.fromMillis(tick)); // same tick: attributes look unchanged

        assertFalse(stamp.isCurrent(), "a new entry inside the same tick is seen by the listing");
    }
}
