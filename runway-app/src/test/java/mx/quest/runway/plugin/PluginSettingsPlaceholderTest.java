package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * A plugin settings file is loaded into the environment, where Spring resolves {@code ${...}}. A value typed into a
 * plugin's settings form must stay the literal text, never pull another property (the master key) into a plugin's
 * output.
 */
class PluginSettingsPlaceholderTest {

    @Test
    void aPlaceholderInAPluginSettingStaysLiteralText() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("host",
                Map.of("runway.security.secret-key", "TOPSECRET")));
        env.getPropertySources().addFirst(PluginContextInitializer.confinedTo("discoverability", new MapPropertySource(
                "plugin-settings/discoverability.yml", Map.of(
                        "runway.plugin.discoverability.robots.custom", "Disallow: /${runway.security.secret-key}",
                        "runway.plugin.discoverability.indexnow.batch-seconds", 60))));

        String key = "runway.plugin.discoverability.robots.custom";
        assertEquals("Disallow: /${runway.security.secret-key}", env.getProperty(key));
        assertEquals("Disallow: /${runway.security.secret-key}",
                Binder.get(env).bind(key, String.class).get());
        assertEquals(60, Binder.get(env).bind("runway.plugin.discoverability.indexnow.batch-seconds", Integer.class)
                .get(), "non-text values are kept as they are");
    }

    /**
     * A <code>${</code> with no closing brace is not a placeholder, and Spring leaves an escape in front
     * of it in place. Escaped anyway, the plugin read <code>\${open</code> and the form listed the key as pending a
     * restart after every start (stored text and running value never matched).
     */
    @Test
    void anUnterminatedPlaceholderReachesThePluginUnchanged() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(PluginContextInitializer.confinedTo("p", new MapPropertySource(
                "plugin-settings/p.yml", Map.of(
                        "runway.plugin.p.custom", "Literal: ${open",
                        "runway.plugin.p.mixed", "${open then ${runway.security.secret-key}"))));
        env.getPropertySources().addLast(new MapPropertySource("host", Map.of("runway.security.secret-key", "TOP")));

        assertEquals("Literal: ${open", env.getProperty("runway.plugin.p.custom"));
        assertEquals("Literal: ${open", Binder.get(env).bind("runway.plugin.p.custom", String.class).get());
        assertEquals("${open then ${runway.security.secret-key}", env.getProperty("runway.plugin.p.mixed"));
    }
}
