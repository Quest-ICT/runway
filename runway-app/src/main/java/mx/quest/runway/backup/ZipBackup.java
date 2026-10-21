package mx.quest.runway.backup;

import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;

/**
 * The host-side {@link BackupWriter}/{@link BackupReader} implementations that scope a {@link
 * mx.quest.runway.spi.BackupParticipant}'s entries under {@code plugin-data/<key>/} inside a site archive.
 *
 * <p><b>Non-closing entry streams.</b> A participant writes each entry with
 * try-with-resources, so closing the per-entry stream must finalize <em>that entry</em> — not the whole
 * {@link ZipOutputStream}, which would truncate the archive after the first participant entry. {@link
 * Writer#put} therefore returns a stream whose {@code close()} calls {@code closeEntry()} and never touches
 * the underlying zip.
 *
 * <p><b>Entry names are checked</b> on both sides ({@link #requireSafeEntry}): a participant can neither write nor
 * look up a name outside its own namespace.
 */
final class ZipBackup {

    private ZipBackup() {
    }

    /**
     * Refuses an entry name that is not a plain relative path inside the participant's namespace:
     * blank, absolute ({@code /x}, {@code C:x}), containing a backslash or a NUL, or with an empty, {@code .} or
     * {@code ..} segment. Such a name would let one participant write outside {@code plugin-data/<key>/} in the
     * archive (and an extractor that trusts names would follow it on disk), so it is a participant bug, refused
     * loudly rather than normalised.
     *
     * @return {@code entry}, unchanged
     * @throws IllegalArgumentException if the name is unsafe
     */
    static String requireSafeEntry(String entry) {
        if (entry == null || entry.isBlank() || entry.startsWith("/") || entry.indexOf('\\') >= 0
                || entry.indexOf('\0') >= 0 || (entry.length() > 1 && entry.charAt(1) == ':')) {
            throw new IllegalArgumentException("unsafe backup entry name: '" + entry + "'");
        }
        for (String segment : entry.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("unsafe backup entry name: '" + entry + "'");
            }
        }
        return entry;
    }

    /** Writes a participant's entries under {@code plugin-data/<key>/} into the shared archive. */
    static final class Writer implements BackupWriter {

        private final ZipOutputStream zip;
        private final String prefix;

        Writer(ZipOutputStream zip, String participantKey) {
            this.zip = zip;
            this.prefix = "plugin-data/" + participantKey + "/";
        }

        @Override
        public OutputStream put(String entry) throws IOException {
            zip.putNextEntry(new ZipEntry(prefix + requireSafeEntry(entry)));
            return new EntryStream(zip);
        }
    }

    /** Reads a participant's entries (pre-collected into memory, keyed by participant-relative name). */
    static final class Reader implements BackupReader {

        private final Map<String, byte[]> entries;

        Reader(Map<String, byte[]> entries) {
            this.entries = entries;
        }

        @Override
        public boolean has(String entry) {
            return entries.containsKey(requireSafeEntry(entry));
        }

        @Override
        public InputStream open(String entry) throws IOException {
            byte[] bytes = entries.get(requireSafeEntry(entry));
            if (bytes == null) {
                throw new IOException("backup entry not found: " + entry);
            }
            return new ByteArrayInputStream(bytes);
        }
    }

    /** A stream over one zip entry: {@code close()} finalizes the entry, never the underlying archive. */
    private static final class EntryStream extends FilterOutputStream {

        private final ZipOutputStream zip;

        EntryStream(ZipOutputStream zip) {
            super(zip);
            this.zip = zip;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); // FilterOutputStream's default is byte-by-byte — delegate the bulk write
        }

        @Override
        public void close() throws IOException {
            flush();
            zip.closeEntry(); // finalize this entry only; the host owns the ZipOutputStream lifecycle
        }
    }
}
