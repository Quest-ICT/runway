package mx.quest.runway.spi.model;

import java.io.IOException;
import java.io.InputStream;

/**
 * A host-provided source a {@link mx.quest.runway.spi.BackupParticipant} reads its per-site
 * data from during a restore. As with {@link BackupWriter}, the host scopes every entry under
 * the participant's own namespace. Restore should be idempotent — importing the same backup
 * twice yields the same state.
 */
public interface BackupReader {

    /**
     * Whether the backup contains the given participant-relative entry. A restore should
     * tolerate a missing entry (an older backup, or a feature not yet used when it was taken).
     *
     * @param entry the participant-relative entry name (never {@code null})
     * @return {@code true} if the entry is present
     */
    boolean has(String entry);

    /**
     * Opens a stream to read one named entry.
     *
     * @param entry the participant-relative entry name (never {@code null})
     * @return an open stream to read the entry's bytes; the caller must close it
     * @throws IOException if the entry is absent or cannot be opened
     */
    InputStream open(String entry) throws IOException;
}
