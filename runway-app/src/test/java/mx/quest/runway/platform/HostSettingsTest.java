package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

/** Which keys the host sets, in every form an operator writes them. */
class HostSettingsTest {

    @Test
    void aListWrittenAsAYamlListCountsAsSetOnTheHost(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, """
                runway:
                  csp: { mode: enforce }
                  proxy:
                    trusted-cidrs: [10.0.0.0/8, 127.0.0.1/32]
                  operator: { ui-languages: [en, es, fr] }
                logging:
                  level:
                    mx.quest.runway: DEBUG
                """);
        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                "Config resource 'file [external/application.yml]'", new FileSystemResource(file))) {
            env.getPropertySources().addLast(source);
        }
        env.getPropertySources().addLast(new MapPropertySource(KernelSettingsInitializer.SOURCE,
                Map.of("runway.proxy.trusted-cidrs", "192.168.1.1/32")));
        HostSettings host = new HostSettings(env);

        assertEquals(Optional.of("external/application.yml"), host.origin("runway.proxy.trusted-cidrs"));
        assertTrue(host.setOnHost("runway.operator.ui-languages"));
        assertTrue(host.setOnHost("runway.csp.mode"), "a scalar, as before");
        assertFalse(host.setOnHost("runway.csp.extra-hosts"), "a key the host does not set");
        assertTrue(host.setOnHost("logging.level.mx.quest.runway"));
        assertFalse(host.setOnHost("logging.level.mx.quest"), "a named child is another key, not this one");
    }

    @Test
    void anIndexedEnvironmentVariableCountsToo() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace("systemEnvironment", new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("RUNWAY_PROXY_TRUSTEDCIDRS_0", "10.0.0.0/8")));
        assertEquals(Optional.of("an environment variable"),
                new HostSettings(env).origin("runway.proxy.trusted-cidrs"));
    }

    /** Spring never binds key[0] into a scalar, so a scalar written as a one-item list sets nothing: not locked. */
    @Test
    void aScalarWrittenAsAOneItemListIsNotSetOnTheHost(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "runway:\n  csp:\n    mode: [enforce]\n");
        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                "Config resource 'file [external/application.yml]'", new FileSystemResource(file))) {
            env.getPropertySources().addLast(source);
        }
        assertFalse(new HostSettings(env).setOnHost("runway.csp.mode"));
    }
}
