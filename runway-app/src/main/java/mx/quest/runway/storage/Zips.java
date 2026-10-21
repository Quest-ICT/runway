package mx.quest.runway.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Zip-entry read helpers that <b>bound per-entry decompression</b> — the single guard against a
 * decompression bomb (a tiny archive entry that inflates to gigabytes) exhausting the heap or the
 * disk while an operator-supplied archive is read.
 *
 * <p>Every place that reads an entry out of an untrusted zip — a site backup restore, a
 * seed-template import — streams it through {@link #copyBounded} / {@link #readBounded} with an
 * explicit byte cap, so a single crafted entry is <b>refused past the cap</b> rather than read in
 * full. This class is the one source of truth for that check; each caller picks the limit
 * appropriate to what the entry legitimately holds (a few MB for a tiny seed file, tens/hundreds
 * of MB for a site asset).
 *
 * <p>The cap is per <em>entry</em>, not per archive: a backup with many legitimate files is fine;
 * only a single entry whose decompressed size blows past the limit is rejected. Neither stream is
 * ever closed here — the caller owns their lifecycle (the input is typically one entry of a shared
 * {@link java.util.zip.ZipInputStream} the loop keeps reading).
 */
public final class Zips {

    private Zips() {
    }

    private static final int BUFFER_BYTES = 8192;

    /**
     * Copies {@code in} to {@code out}, aborting as soon as more than {@code maxBytes} have been read
     * — so the overflowing bytes are never written. Reads {@code in} to end-of-entry on success
     * (leaving a shared {@link java.util.zip.ZipInputStream} positioned for the next entry). Closes
     * neither stream.
     *
     * @param maxBytes the inclusive per-entry cap; exactly {@code maxBytes} is allowed, one more is not
     * @return the number of bytes copied (always {@code <= maxBytes})
     * @throws IOException if the entry's decompressed size exceeds {@code maxBytes}, or on an I/O error
     */
    public static long copyBounded(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("a zip entry exceeds the per-entry limit of " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    /**
     * Reads {@code in} fully into memory, capped at {@code maxBytes}. Does not close {@code in}.
     *
     * @param maxBytes the inclusive per-entry cap; exactly {@code maxBytes} is allowed, one more is not
     * @throws IOException if the entry's decompressed size exceeds {@code maxBytes}, or on an I/O error
     */
    public static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copyBounded(in, out, maxBytes);
        return out.toByteArray();
    }
}
