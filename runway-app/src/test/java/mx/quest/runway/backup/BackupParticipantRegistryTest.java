package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mx.quest.runway.spi.BackupParticipant;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;
import mx.quest.runway.spi.model.SiteRef;
import org.junit.jupiter.api.Test;

/** The registry collects participants by archive-namespace key (fail-fast on a duplicate) and finds by key. */
class BackupParticipantRegistryTest {

    private static BackupParticipant participant(String key) {
        return new BackupParticipant() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public void export(BackupWriter out, SiteRef site) {
                // no-op for the test
            }

            @Override
            public void importInto(BackupReader in, SiteRef site) {
                // no-op for the test
            }
        };
    }

    @Test
    void aDuplicateKeyFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new BackupParticipantRegistry(List.of(participant("dup"), participant("dup"))));
    }

    @Test
    void collectsAndFindsByKey() {
        BackupParticipantRegistry registry = new BackupParticipantRegistry(List.of(participant("a"), participant("z")));
        assertEquals(2, registry.all().size());
        assertTrue(registry.find("a").isPresent());
        assertTrue(registry.find("missing").isEmpty());
    }
}
