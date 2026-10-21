package mx.quest.runway.spi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RunwaySpiTest {

    @Test
    void versionParsesAsPlainMajorMinorPatch() {
        assertTrue(RunwaySpi.VERSION.matches("\\d+\\.\\d+\\.\\d+"),
                "RunwaySpi.VERSION must be plain MAJOR.MINOR.PATCH so the plugin resolver's "
                        + "segment-wise integer compare stays trivial; was: " + RunwaySpi.VERSION);
    }

    @Test
    void runtimeVersionReportsTheRunningSpi() {
        // Read reflectively, as a plugin compiled against an older SPI effectively does: the method body runs in the
        // running runway-spi, so it reports that jar's VERSION, never an inlined copy.
        assertTrue(RunwaySpi.runtimeVersion().equals(RunwaySpi.VERSION));
    }
}
