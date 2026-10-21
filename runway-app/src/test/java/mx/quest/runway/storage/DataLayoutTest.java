package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The data-layout marker: written when absent, accepted when supported, refused when newer or broken. */
class DataLayoutTest {

    @TempDir
    Path external;

    private Path marker() {
        return external.resolve("layout.yml");
    }

    private void marker(String body) throws IOException {
        Files.writeString(marker(), body, StandardCharsets.UTF_8);
    }

    // --- absent ----------------------------------------------------------------------------------------------

    /** Every install that predates the marker is layout 1 by definition, so an absent marker is written as 1. */
    @Test
    void anAbsentMarkerIsWrittenAsTheSupportedVersion() throws IOException {
        DataLayout.Result result = DataLayout.verify(external, true);

        assertEquals(DataLayout.Outcome.WRITTEN, result.outcome());
        assertEquals(1, result.version());
        String body = Files.readString(marker());
        assertTrue(body.contains("version: 1"), body);
        assertTrue(body.startsWith("# Runway data-layout marker"), "a header says what the file is: " + body);
        assertEquals(1, DataLayout.read(marker()), "what is written reads back");
    }

    @Test
    void aSecondCheckFindsTheWrittenMarkerAndLeavesItAlone() throws IOException {
        DataLayout.verify(external, true);
        byte[] first = Files.readAllBytes(marker());

        DataLayout.Result again = DataLayout.verify(external, true);

        assertEquals(DataLayout.Outcome.SUPPORTED, again.outcome());
        assertArrayEquals(first, Files.readAllBytes(marker()));
    }

    @Test
    void anAbsentFolderIsCreatedWhenWritingIsAllowed() {
        Path fresh = external.resolve("not-yet");

        DataLayout.Result result = DataLayout.verify(fresh, true);

        assertEquals(DataLayout.Outcome.WRITTEN, result.outcome());
        assertTrue(Files.isRegularFile(fresh.resolve("layout.yml")));
    }

    /** A fresh WIZARD boot: nothing exists, nothing is protected, nothing is written (not even the folder). */
    @Test
    void nothingIsWrittenWhenWritingIsNotAllowed() {
        Path fresh = external.resolve("not-yet");

        DataLayout.Result result = DataLayout.verify(fresh, false);

        assertEquals(DataLayout.Outcome.ABSENT_NOT_WRITTEN, result.outcome());
        assertEquals(1, result.version());
        assertFalse(Files.exists(fresh), "the folder must not be created");
    }

    /** An unwritable folder must not turn into a new startup failure for an install that booted before the marker. */
    @Test
    void aWriteFailureIsReportedNotThrown() throws IOException {
        Path notADirectory = external.resolve("a-file");
        Files.writeString(notADirectory, "x");

        DataLayout.Result result = DataLayout.verify(notADirectory, true);

        assertEquals(DataLayout.Outcome.ABSENT_WRITE_FAILED, result.outcome());
        assertNotNull(result.failure());
    }

    // --- present and supported ---------------------------------------------------------------------------------

    @Test
    void theSupportedVersionIsAcceptedAndTheFileIsNotRewritten() throws IOException {
        marker("# an operator's note\nversion: 1\n");
        byte[] before = Files.readAllBytes(marker());

        DataLayout.Result result = DataLayout.verify(external, true);

        assertEquals(DataLayout.Outcome.SUPPORTED, result.outcome());
        assertEquals(1, result.version());
        assertArrayEquals(before, Files.readAllBytes(marker()), "a supported marker is never rewritten");
    }

    /** Like every store, unknown keys are tolerated — a newer build may annotate the file without a version bump. */
    @Test
    void unknownKeysAreTolerated() throws IOException {
        marker("version: 1\nwrittenBy: 1.4.0\nnotes: [a, b]\n");

        assertEquals(DataLayout.Outcome.SUPPORTED, DataLayout.verify(external, true).outcome());
    }

    @Test
    void aQuotedRunOfDigitsIsAWholeNumber() throws IOException {
        marker("version: \"1\"\n");

        assertEquals(1, DataLayout.verify(external, true).version());
    }

    // --- newer: downgrade protection ---------------------------------------------------------------------------

    @Test
    void aNewerLayoutRefusesToStartAndIsNotTouched() throws IOException {
        marker("version: 2\n");
        byte[] before = Files.readAllBytes(marker());

        DataLayoutException refused = assertThrows(DataLayoutException.class, () -> DataLayout.verify(external, true));

        assertTrue(refused.isNewer());
        assertEquals(marker(), refused.marker());
        assertTrue(refused.getMessage().contains("version 2"), refused.getMessage());
        assertTrue(refused.getMessage().contains("up to version 1"), refused.getMessage());
        assertArrayEquals(before, Files.readAllBytes(marker()), "a newer marker is never overwritten");
    }

    /** The refusal holds in WIZARD mode too: writing is not the only harm, reading a newer tree is guessing. */
    @Test
    void aNewerLayoutIsRefusedEvenWhenWritingIsNotAllowed() throws IOException {
        marker("version: 3\n");

        assertThrows(DataLayoutException.class, () -> DataLayout.verify(external, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"version: 99999999999\n", "version: 123456789012345678901234567890\n"})
    void aVersionBeyondAnIntIsStillNewerNotMalformed(String body) throws IOException {
        marker(body);

        DataLayoutException refused = assertThrows(DataLayoutException.class, () -> DataLayout.verify(external, true));
        assertTrue(refused.isNewer(), refused.getMessage());
    }

    // --- malformed ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "",                       // empty
            "# only a comment\n",     // no content
            "version: [1\n",          // not YAML
            "- 1\n- 2\n",             // a list, not a mapping
            "just a scalar\n",        // a scalar, not a mapping
            "layout: 1\n",            // no version key
            "version:\n",             // version null
            "version: abc\n",         // not a number
            "version: 1.5\n",         // not whole
            "version: 0\n",           // below 1
            "version: -2\n",          // negative
            "version: \"1a\"\n",      // a string that is not digits
            "version: true\n",        // a boolean
    })
    void aMalformedMarkerRefusesToStart(String body) throws IOException {
        marker(body);
        byte[] before = Files.readAllBytes(marker());

        DataLayoutException refused = assertThrows(DataLayoutException.class, () -> DataLayout.verify(external, true));

        assertFalse(refused.isNewer(), refused.getMessage());
        assertTrue(refused.getMessage().contains("cannot be read"), refused.getMessage());
        assertArrayEquals(before, Files.readAllBytes(marker()), "a malformed marker is never 'repaired' by guessing");
    }
}
