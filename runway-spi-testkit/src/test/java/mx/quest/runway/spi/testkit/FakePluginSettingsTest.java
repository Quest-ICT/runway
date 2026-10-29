package mx.quest.runway.spi.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.model.SettingsView;
import org.junit.jupiter.api.Test;

/** The fake answers like the kernel: defaults, then stored values; secrets in clear; unsafe ids refused. */
class FakePluginSettingsTest {

    @Test
    void defaultsAreOverlaidByTheSitesValuesAndABlankDoesNotMaskADefault() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("limit", 50);
        stored.put("title", " ");
        stored.put("extra", "kept");
        stored.put("gone", null);
        FakePluginSettings settings = new FakePluginSettings()
                .defaults(Map.of("enabled", true, "limit", 20, "title", "Hello"))
                .site("acme", stored);
        SettingsView acme = settings.site("acme");
        assertTrue(acme.bool("enabled", false));
        assertEquals(50, acme.integer("limit", 0));
        assertEquals("Hello", acme.string("title", "x"));
        assertEquals("kept", acme.string("extra", "x"));
        assertEquals(20, settings.site("other").integer("limit", 0), "a site with nothing stored gets the defaults");
    }

    @Test
    void secretsAreReturnedInClearPerSite() {
        FakePluginSettings settings = new FakePluginSettings().secret("acme", "smtpPassword", "s3cret");
        assertEquals(Optional.of("s3cret"), settings.secret("acme", "smtpPassword"));
        assertEquals(Optional.empty(), settings.secret("other", "smtpPassword"));
        assertEquals(Optional.empty(), settings.secret("acme", "other"));
    }

    @Test
    void unsafeSiteIdsAreRefusedLikeTheKernelRefusesThem() {
        FakePluginSettings settings = new FakePluginSettings();
        for (String bad : new String[] {null, "", "..", "../x", "a/b", "a|b", "-lead"}) {
            assertThrows(IllegalArgumentException.class, () -> settings.site(bad), "site(" + bad + ")");
            assertThrows(IllegalArgumentException.class, () -> settings.secret(bad, "k"));
        }
    }

    @Test
    void appLevelSecretsAreReturnedInClearAndBlankOnesAreAbsent() {
        FakePluginSettings settings = new FakePluginSettings().appSecret("password", "s3cret").appSecret("blank", " ");
        assertEquals(Optional.of("s3cret"), settings.appSecret("password"));
        assertEquals(Optional.empty(), settings.appSecret("blank"));
        assertEquals(Optional.empty(), settings.appSecret("other"));
        assertEquals(Optional.empty(), settings.appSecret(null));
    }
}
