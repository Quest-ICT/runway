package mx.quest.runway;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import mx.quest.runway.storage.Yaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code runway.*} configuration namespace is documented in the generated Spring configuration
 * metadata (IDE completion, and the source of the key list in CONFIGURATION-AND-DATA-STABILITY.md). Guards two
 * regressions: the configuration processor silently not running (it once did not — JDK 23+ dropped implicit
 * annotation processing), and a key added to {@code application.yml} or read via {@code @Value} without metadata.
 */
class ConfigurationMetadataTest {

    private static Set<String> documentedKeys() throws IOException {
        try (InputStream in = ConfigurationMetadataTest.class.getResourceAsStream(
                "/META-INF/spring-configuration-metadata.json")) {
            assertNotNull(in, "spring-configuration-metadata.json is generated (is the processor on the "
                    + "maven-compiler-plugin annotationProcessorPaths?)");
            JsonNode root = JsonMapper.builder().build().readTree(in);
            Set<String> names = new TreeSet<>();
            root.get("properties").forEach(p -> names.add(p.get("name").asString()));
            return names;
        }
    }

    /** The keys read only via {@code @Value} or straight from the Environment, which no properties class covers. */
    @ParameterizedTest
    @ValueSource(strings = {
            "runway.backups.schedule.cron",
            "runway.demo.first-run.id",
            "runway.demo.first-run.host",
            "runway.demo.first-run.master-username",
            "runway.demo.first-run.master-password",
            "runway.endpoints.max-body-bytes",
            "runway.plugins.max-entry-bytes",
            "runway.security.app-master-id",
            "runway.node.name",
            "runway.plugins.root",
            "runway.storage.external",
    })
    void everyValueAndEnvironmentKeyIsDocumented(String key) throws IOException {
        assertTrue(documentedKeys().contains(key), key + " is missing from the configuration metadata");
    }

    /** Every runway.* default in the shipped application.yml names a documented key (a typo there binds nothing). */
    @Test
    void everyKeyInTheShippedApplicationYmlIsDocumented() throws IOException {
        Map<?, ?> yml;
        try (InputStream in = ConfigurationMetadataTest.class.getResourceAsStream("/application.yml")) {
            yml = Yaml.read(in, Map.class);
        }
        List<String> leaves = new ArrayList<>();
        collect(yml.get("runway"), "runway", leaves);
        Set<String> documented = documentedKeys();
        List<String> undocumented = leaves.stream()
                .filter(k -> !documented.contains(k) && documented.stream().noneMatch(d -> k.startsWith(d + ".")))
                .toList();
        assertTrue(leaves.size() > 50, "the walk found the runway tree: " + leaves.size());
        assertTrue(undocumented.isEmpty(), "application.yml keys with no metadata: " + undocumented);
    }

    private static void collect(Object node, String prefix, List<String> out) {
        if (node instanceof Map<?, ?> map && !map.isEmpty()) {
            map.forEach((k, v) -> collect(v, prefix + "." + k, out));
        } else {
            out.add(prefix);
        }
    }
}
