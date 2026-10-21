package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.plugin.PluginSettingsService.RenderedSetting;
import mx.quest.runway.security.RunwaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * A list the host sets with a gap in its numbering ({@code _0} and {@code _2}, no {@code _1}) does not
 * bind. Server settings must still open and save: the field shows locked with the items that can be read, never a
 * 500 from an unhandled {@code BindException}.
 */
class KernelSettingsHostListGapTest {

    private static KernelSettingsService service(Map<String, Object> hostEnv) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        hostEnv));
        return new KernelSettingsService(TestPlatformSettings.empty(), new HostSettings(env), env,
                new AesGcmSecretCipher(new RunwaySecurityProperties()));
    }

    private static String shown(List<RenderedSetting> rendered, String key) {
        return rendered.stream().filter(r -> r.def().key().equals(key)).findFirst().orElseThrow().value();
    }

    @Test
    void aHostListWithAGapShowsLockedWithTheItemsThatCanBeRead() {
        KernelSettingsService settings = service(Map.of(
                "RUNWAY_PROXY_TRUSTEDCIDRS_0", "10.0.0.0/8",
                "RUNWAY_PROXY_TRUSTEDCIDRS_2", "172.16.0.0/12"));
        List<RenderedSetting> rendered = assertDoesNotThrow(() -> settings.render("server"));
        assertEquals("10.0.0.0/8,172.16.0.0/12", shown(rendered, "runway.proxy.trusted-cidrs"));
        assertTrue(settings.locks("server").containsKey("runway.proxy.trusted-cidrs"), "and locked");
    }

    @Test
    void aSaveThatReadsAHostListWithAGapIsNotA500() {
        // The save's cross-check reads the offered languages to test the default language against them.
        KernelSettingsService settings = service(Map.of(
                "RUNWAY_OPERATOR_UILANGUAGES_0", "en",
                "RUNWAY_OPERATOR_UILANGUAGES_2", "es"));
        Map<String, String> submitted = new HashMap<>();
        submitted.put("runway.operator.default-language", "es");
        assertDoesNotThrow(() -> settings.save("server", submitted));
        assertEquals("en,es", shown(settings.render("server"), "runway.operator.ui-languages"));
    }
}
