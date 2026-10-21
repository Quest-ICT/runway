package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.web.RunwayCspProperties;
import mx.quest.runway.web.RunwayHttpCacheProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

/** The console's kernel settings load as one property source, ranked below everything the host sets. */
class KernelSettingsInitializerTest {

    @Test
    void onlyDeclaredKeysLoadAndListsBecomeCommaSeparated(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, """
                defaultSiteId: main
                settings:
                  runway.csp.mode: enforce
                  runway.csp.extra-hosts: [https://a.test, https://b.test]
                  runway.security.app-master-id: hijacked
                """);
        Map<String, Object> loaded = KernelSettingsInitializer.load(file, new MockEnvironment());
        assertEquals(Map.of("runway.csp.mode", "enforce", "runway.csp.extra-hosts", "https://a.test,https://b.test"),
                loaded);
    }

    @Test
    void anEncryptedTokenWithoutTheKeyIsLeftOutNotBoundEncrypted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, "settings:\n  runway.reload.token: ENC(abc)\n  runway.metrics.scrape-token: plain\n");
        Map<String, Object> loaded = KernelSettingsInitializer.load(file, new MockEnvironment());
        assertFalse(loaded.containsKey("runway.reload.token"));
        assertEquals("plain", loaded.get("runway.metrics.scrape-token"), "a hand-written plaintext passes through");
    }

    @Test
    void aSecretStoredUnderAnotherKeyIsLeftOutAndLoadingCarriesOn(@TempDir Path dir) throws IOException {
        RunwaySecurityProperties other = new RunwaySecurityProperties();
        other.setSecretKey("the-key-the-value-was-written-under-0123456789");
        String stored = new AesGcmSecretCipher(other).encrypt("token-value");
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, "settings:\n  runway.reload.token: \"" + stored
                + "\"\n  runway.metrics.scrape-token: plain\n");
        MockEnvironment env = new MockEnvironment()
                .withProperty("runway.security.secret-key", "a-different-key-9876543210-abcdefghij");
        Map<String, Object> loaded = KernelSettingsInitializer.load(file, env);
        assertFalse(loaded.containsKey("runway.reload.token"), "an unreadable secret is left out, not thrown");
        assertEquals("plain", loaded.get("runway.metrics.scrape-token"), "the other settings still load");
    }

    /**
     * A {@code ${...}} that reached the file (a hand edit, an older release) must not resolve. Checked where it
     * matters — through the binder that feeds the beans whose values go out in public headers — not in the raw map,
     * which held the text verbatim all along.
     */
    @Test
    void aStoredPlaceholderStaysLiteralTextAndNeverPullsInTheMasterKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, """
                settings:
                  runway.csp.report-uri: "/__csp-report?k=${runway.security.secret-key}"
                  runway.csp.default-src: "'self' ${no.such.property}"
                  runway.csp.extra-hosts: [https://a.test, "https://${runway.security.secret-key}.test"]
                  runway.http-cache.public-pages: "public, max-age=${user.name}"
                  runway.metrics.scrape-token: "${runway.security.secret-key}"
                """);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("host",
                Map.of("runway.security.secret-key", "TOPSECRET-0123456789abcdef")));
        env.getPropertySources().addLast(new MapPropertySource(KernelSettingsInitializer.SOURCE,
                KernelSettingsInitializer.load(file, env)));

        RunwayCspProperties csp = Binder.get(env).bind("runway.csp", RunwayCspProperties.class).get();
        String headers = csp.reportingEndpointsHeader() + "\n" + csp.buildPolicy();
        assertFalse(headers.contains("TOPSECRET"), headers);
        assertTrue(headers.contains("/__csp-report?k=${runway.security.secret-key}"), "the text stays as written");
        assertTrue(headers.contains("default-src 'self' ${no.such.property}"), headers);
        assertTrue(headers.contains("https://${runway.security.secret-key}.test"), "a list item too: " + headers);
        RunwayHttpCacheProperties cache = Binder.get(env).bind("runway.http-cache", RunwayHttpCacheProperties.class)
                .get();
        assertEquals("public, max-age=${user.name}", cache.publicPagesOrDefault());
        assertEquals("${runway.security.secret-key}", env.getProperty("runway.metrics.scrape-token"),
                "a token too, and the Environment does not throw on an unknown name");
        assertEquals("'self' ${no.such.property}", env.getProperty("runway.csp.default-src"));
    }

    /** An unterminated <code>${</code> is not a placeholder, so it binds as written, with no backslash. */
    @Test
    void anUnterminatedPlaceholderBindsAsWritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, "settings:\n  runway.csp.default-src: \"'self' ${open\"\n");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource(KernelSettingsInitializer.SOURCE,
                KernelSettingsInitializer.load(file, env)));
        assertEquals("'self' ${open", env.getProperty("runway.csp.default-src"));
        assertEquals("'self' ${open", Binder.get(env).bind("runway.csp.default-src", String.class).get());
    }

    @Test
    void noFileOrNoSettingsLoadsNothing(@TempDir Path dir) throws IOException {
        assertTrue(KernelSettingsInitializer.load(dir.resolve("absent.yml"), new MockEnvironment()).isEmpty());
        Files.writeString(dir.resolve("p.yml"), "defaultSiteId: main\n");
        assertTrue(KernelSettingsInitializer.load(dir.resolve("p.yml"), new MockEnvironment()).isEmpty());
    }

    @Test
    void itRanksJustAboveTheShippedDefaultsSoTheHostWins() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("commandLineArgs", Map.of()));
        sources.addLast(new MapPropertySource("Config resource 'file [external/application.yml]' via location "
                + "'optional:file:external/application.yml'", Map.of()));
        sources.addLast(new MapPropertySource("Config resource 'class path resource [application.yml]' via location "
                + "'optional:classpath:/'", Map.of()));
        KernelSettingsInitializer.install(sources, new MapPropertySource(KernelSettingsInitializer.SOURCE, Map.of()));
        List<String> order = sources.stream().map(PropertySource::getName).toList();
        assertEquals(2, order.indexOf(KernelSettingsInitializer.SOURCE), order.toString());
    }
}
