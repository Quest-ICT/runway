package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A catch-all trusted proxy ({@code /0}) set in {@code application.yml} or an older {@code platform.yml} is not
 * refused at startup (an existing deployment must still boot), but the log says what it does.
 */
@ExtendWith(OutputCaptureExtension.class)
class ForwardedHeaderCatchAllWarningTest {

    private static RunwayProxyProperties proxy(String... cidrs) {
        RunwayProxyProperties properties = new RunwayProxyProperties();
        properties.setForwardedHeaders(true);
        properties.setTrustedCidrs(List.of(cidrs));
        return properties;
    }

    @Test
    void aCatchAllIsWarnedAboutAtStartup(CapturedOutput output) {
        new ForwardedHeaderConfig().trustedForwardedHeaderFilterRegistration(proxy("10.0.0.5/32", "0.0.0.0/0"));
        assertTrue(output.getAll().contains("covers every address of a family"), output.getAll());
    }

    /** Two /1 halves trust every IPv4 client exactly as a /0 does, and are warned about the same way. */
    @Test
    void complementaryHalvesAreWarnedAboutToo(CapturedOutput output) {
        new ForwardedHeaderConfig().trustedForwardedHeaderFilterRegistration(proxy("0.0.0.0/1", "128.0.0.0/1"));
        assertTrue(output.getAll().contains("covers every address of a family"), output.getAll());
    }

    @Test
    void aProxysOwnAddressIsNot(CapturedOutput output) {
        new ForwardedHeaderConfig().trustedForwardedHeaderFilterRegistration(proxy("10.0.0.5/32", "::1/128"));
        assertFalse(output.getAll().contains("covers every address"), output.getAll());
    }
}
