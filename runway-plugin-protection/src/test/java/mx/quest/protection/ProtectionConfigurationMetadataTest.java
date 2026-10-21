package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plugin's own {@code runway.plugin.protection.*} keys get Spring configuration metadata (IDE completion and
 * type checks). Guards the regression where the configuration processor silently never ran: on JDK 23+ it must be
 * on the compiler's {@code annotationProcessorPaths}, not merely on the classpath.
 *
 * <p>The file is read from <b>this module's</b> compiled classes, never through the class loader: the kernel's
 * own {@code META-INF/spring-configuration-metadata.json} is on the test classpath too (runway-app is a
 * {@code provided} dependency) and would otherwise be found first.
 */
class ProtectionConfigurationMetadataTest {

    private static Map<String, JsonNode> properties() throws IOException, URISyntaxException {
        Path classes = Path.of(ProtectionProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path metadata = classes.resolve("META-INF/spring-configuration-metadata.json");
        assertTrue(Files.isRegularFile(metadata), "the plugin's metadata is generated at " + metadata
                + " (is spring-boot-configuration-processor on the compiler's annotationProcessorPaths?)");
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(metadata));
        Map<String, JsonNode> byName = new HashMap<>();
        root.get("properties").forEach(p -> byName.put(p.get("name").asString(), p));
        return byName;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "runway.plugin.protection.rate-limit.capacity",
            "runway.plugin.protection.rate-limit.refill-per-second",
            "runway.plugin.protection.rate-limit.auto-block.enabled",
            "runway.plugin.protection.allowlist",
            "runway.plugin.protection.login.max-attempts",
            "runway.plugin.protection.login.captcha.provider",
            "runway.plugin.protection.login.captcha.secret-key",
            "runway.plugin.protection.remember-device.days",
    })
    void everyPluginKeyIsInTheMetadata(String key) throws Exception {
        assertTrue(properties().containsKey(key), key + " is missing from the plugin's configuration metadata");
    }

    @Test
    void theMetadataCarriesTypesAndDefaults() throws Exception {
        Map<String, JsonNode> props = properties();
        JsonNode capacity = props.get("runway.plugin.protection.rate-limit.capacity");
        assertEquals("java.lang.Integer", capacity.get("type").asString());
        assertEquals(120, capacity.get("defaultValue").asInt());
        assertEquals(30, props.get("runway.plugin.protection.remember-device.days").get("defaultValue").asInt());
    }

    @Test
    void onlyThePluginsOwnPrefixIsDescribedHere() throws Exception {
        assertFalse(properties().isEmpty());
        properties().keySet().forEach(name -> assertTrue(name.startsWith("runway.plugin.protection."),
                name + " is outside the plugin's prefix — the kernel's metadata was picked up instead"));
    }
}
