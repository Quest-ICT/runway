package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** TLS fail-fast: never start half-secured — a misconfigured enablement dies with a precise message. */
class TlsStartupTest {

    private static TlsStartup startup(RunwayTlsProperties tls) {
        return new TlsStartup(tls, new MockEnvironment());
    }

    @Test
    void disabledTlsSkipsAllValidation() {
        assertDoesNotThrow(() -> startup(new RunwayTlsProperties()).validate());
    }

    @Test
    void redirectWithoutTlsFailsFast() {
        RunwayTlsProperties tls = new RunwayTlsProperties();
        tls.getRedirectHttp().setEnabled(true); // but tls.enabled stays false
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> startup(tls).validate());
        assertTrue(ex.getMessage().contains("requires runway.tls.enabled"), ex.getMessage());
    }

    @Test
    void enabledWithBlankKeystoreFailsFast() {
        RunwayTlsProperties tls = new RunwayTlsProperties();
        tls.setEnabled(true);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> startup(tls).validate());
        assertTrue(ex.getMessage().contains("keystore is empty"), ex.getMessage());
    }

    @Test
    void enabledWithMissingKeystoreFileFailsFast(@TempDir Path tmp) {
        RunwayTlsProperties tls = new RunwayTlsProperties();
        tls.setEnabled(true);
        tls.setKeystore(tmp.resolve("nope.p12").toString());
        tls.setKeystorePassword("secret");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> startup(tls).validate());
        assertTrue(ex.getMessage().contains("missing or unreadable"), ex.getMessage());
    }

    @Test
    void enabledWithBlankPasswordFailsFast(@TempDir Path tmp) throws IOException {
        Path keystore = Files.createFile(tmp.resolve("ks.p12"));
        RunwayTlsProperties tls = new RunwayTlsProperties();
        tls.setEnabled(true);
        tls.setKeystore(keystore.toString());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> startup(tls).validate());
        assertTrue(ex.getMessage().contains("keystore-password is empty"), ex.getMessage());
    }

    /** Copies the bundled localhost test keystore (alias {@code runway}, password {@code test-password}). */
    private static Path realKeystore(Path tmp) throws IOException {
        Path keystore = tmp.resolve("ks.p12");
        try (var in = TlsStartupTest.class.getResourceAsStream("/tls/test-keystore.p12")) {
            Files.copy(in, keystore);
        }
        return keystore;
    }

    private static RunwayTlsProperties enabled(Path keystore, String password) {
        RunwayTlsProperties tls = new RunwayTlsProperties();
        tls.setEnabled(true);
        tls.setKeystore(keystore.toString());
        tls.setKeystorePassword(password);
        return tls;
    }

    @Test
    void aCorruptKeystoreFailsFastWithAPreciseMessage(@TempDir Path tmp) throws IOException {
        Path garbage = Files.writeString(tmp.resolve("ks.p12"), "this is not a keystore");
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> startup(enabled(garbage, "secret")).validate());
        assertTrue(ex.getMessage().contains("could not be opened"), ex.getMessage());
    }

    @Test
    void aWrongPasswordFailsFastAtStartupNotInsideJetty(@TempDir Path tmp) throws IOException {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> startup(enabled(realKeystore(tmp), "wrong-password")).validate());
        assertTrue(ex.getMessage().contains("could not be opened"), ex.getMessage());
    }

    @Test
    void aMissingKeyAliasFailsFastNamingTheAlias(@TempDir Path tmp) throws IOException {
        RunwayTlsProperties tls = enabled(realKeystore(tmp), "test-password");
        tls.setKeyAlias("no-such-entry");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> startup(tls).validate());
        assertTrue(ex.getMessage().contains("no-such-entry"), ex.getMessage());
    }

    @Test
    void aRedirectStatusThatIsNotARedirectFailsFast(@TempDir Path tmp) throws IOException {
        Path keystore = realKeystore(tmp);
        for (int bad : new int[] {200, 404, 300, 303, 0, 9999}) {
            RunwayTlsProperties tls = enabled(keystore, "test-password");
            tls.getRedirectHttp().setEnabled(true);
            tls.getRedirectHttp().setStatus(bad);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> startup(tls).validate(),
                    () -> "status " + bad);
            assertTrue(ex.getMessage().contains("redirect-http.status is " + bad), ex.getMessage());
        }
        for (int good : new int[] {301, 302, 307, 308}) {
            RunwayTlsProperties tls = enabled(keystore, "test-password");
            tls.getRedirectHttp().setEnabled(true);
            tls.getRedirectHttp().setStatus(good);
            assertDoesNotThrow(() -> startup(tls).validate(), "status " + good);
        }
        RunwayTlsProperties off = new RunwayTlsProperties();
        off.getRedirectHttp().setStatus(200); // the redirect listener is off, so its status is never used
        assertDoesNotThrow(() -> startup(off).validate());
    }

    @Test
    void aCompleteConfigurationPasses(@TempDir Path tmp) throws IOException {
        assertDoesNotThrow(() -> startup(enabled(realKeystore(tmp), "test-password")).validate());
    }
}
