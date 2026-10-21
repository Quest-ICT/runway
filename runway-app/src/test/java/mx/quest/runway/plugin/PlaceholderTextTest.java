package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * A stored setting reads back exactly as written once it is in the {@code Environment}: no <code>${...}</code>
 * resolves to another property, and no escape is left in front of a <code>${</code> Spring would not have resolved
 * anyway (the plugin would get <code>\${</code>, and the form would show a restart that never stops pending).
 */
class PlaceholderTextTest {

    private static final List<String> TEXTS = List.of(
            "plain",
            "Disallow: /${runway.security.secret-key}",
            "Literal: ${open",
            "${open",
            "a ${open and ${runway.security.secret-key}",
            "${runway.security.secret-key} then ${open",
            "${a ${runway.security.secret-key}",
            "${a:${runway.security.secret-key}}",
            "${runway.security.secret-key:x}",
            "}${runway.security.secret-key",
            "\\${runway.security.secret-key}",
            "\\\\${runway.security.secret-key}",
            "$${runway.security.secret-key}}",
            "${}",
            "${",
            "x\n${runway.security.secret-key}\ny",
            "#{'${runway.security.secret-key}'}",
            "${x{}",
            "${{}${runway.security.secret-key}}",
            "${x{} ${runway.security.secret-key}",
            "{${runway.security.secret-key}");

    @Test
    void everyTextReadsBackAsWrittenAndNeverResolves() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("host",
                Map.of("runway.security.secret-key", "TOPSECRET", "open", "OPENED", "a", "AAA")));
        Map<String, Object> stored = new LinkedHashMap<>();
        for (int i = 0; i < TEXTS.size(); i++) {
            stored.put("runway.plugin.p.k" + i, PlaceholderText.literal(TEXTS.get(i)));
        }
        env.getPropertySources().addFirst(new MapPropertySource("plugin-settings/p.yml", stored));
        for (int i = 0; i < TEXTS.size(); i++) {
            String key = "runway.plugin.p.k" + i;
            String expected = TEXTS.get(i);
            String viaEnv = env.getProperty(key);
            assertEquals(expected, viaEnv, "environment, text #" + i);
            assertEquals(expected, Binder.get(env).bind(key, String.class).get(), "binder, text #" + i);
            assertFalse(viaEnv.contains("TOPSECRET") || viaEnv.contains("OPENED") || viaEnv.contains("AAA"), viaEnv);
        }
    }

    @Test
    void anUnterminatedPlaceholderIsLeftAlone() {
        assertEquals("Literal: ${open", PlaceholderText.literal("Literal: ${open"));
        assertEquals("\\${a} ${open", PlaceholderText.literal("${a} ${open"));
        assertEquals("plain", PlaceholderText.literal("plain"));
    }
}
