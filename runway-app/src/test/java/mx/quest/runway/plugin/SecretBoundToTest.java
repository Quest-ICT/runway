package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * {@code secretBoundTo} on a secret setting. A blank secret field keeps the stored
 * secret only while every bound setting keeps its value; a change to one drops the secret in the same write, so
 * a settings editor who cannot read the SMTP password cannot have the plugin send it to a host they choose. Both
 * realms (per-tenant {@link SitePluginConfigService}, app-level {@link PluginSettingsService}) and the lenient
 * descriptor parsing.
 */
class SecretBoundToTest {

    private static final String SITE = "acme";
    private static final String PLUGIN = "mailer";

    /** The SMTP shape a contact-form plugin has: where the password goes, and the password bound to it. */
    private static List<PluginSettingDef> smtpSchema(List<String> bound) {
        return List.of(
                new PluginSettingDef("smtpHost", "string", null, "l", null),
                new PluginSettingDef("smtpPort", "int", 587, "l", null),
                new PluginSettingDef("smtpAuth", "boolean", false, "l", null),
                new PluginSettingDef("smtpUsername", "string", null, "l", null),
                new PluginSettingDef("smtpPassword", "secret", null, "l", null, null, null, null, null, null, null,
                        null, bound),
                new PluginSettingDef("fromAddress", "string", null, "l", null));
    }

    private static final List<PluginSettingDef> BOUND = smtpSchema(List.of("smtpHost", "smtpPort", "smtpUsername"));

    private static Map<String, String> form(String host, String port, String user, String password, String from) {
        Map<String, String> form = new HashMap<>();
        form.put("smtpHost", host);
        form.put("smtpPort", port);
        form.put("smtpAuth", "true");
        form.put("smtpUsername", user);
        form.put("smtpPassword", password);
        form.put("fromAddress", from);
        return form;
    }

    private static SitePluginConfigService site(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new SitePluginConfigService(StorageLayout.from(props), cipher(), event -> { });
    }

    private static PluginSettingsService app(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new PluginSettingsService(StorageLayout.from(props), cipher());
    }

    private static SecretCipher cipher() {
        return new SecretCipher() {
            @Override public boolean isAvailable() {
                return true;
            }
            @Override public String encrypt(String plaintext) {
                return "ENC(" + new StringBuilder(plaintext).reverse() + ")";
            }
            @Override public String decrypt(String value) {
                return new StringBuilder(value.substring(4, value.length() - 1)).reverse().toString();
            }
            @Override public boolean isEncrypted(String value) {
                return value != null && value.startsWith("ENC(") && value.endsWith(")");
            }
        };
    }

    // --- per-tenant ------------------------------------------------------------------------------------

    @Test
    void repointingTheHostWithABlankPasswordDropsTheStoredPassword(@TempDir Path external) {
        SitePluginConfigService svc = site(external);
        assertEquals(List.of(), svc.save(SITE, PLUGIN, BOUND,
                form("mail.example.com", "587", "u", "TOPSECRET-123", "a@example.com")));
        assertEquals("ENC(321-TERCESPOT)", svc.config(SITE, PLUGIN).get("smtpPassword"));

        // The reproduction: host -> attacker's listener, password left blank.
        List<String> dropped = svc.save(SITE, PLUGIN, BOUND, form("127.0.0.1", "2525", "u", "", "a@example.com"));
        assertEquals(List.of("smtpPassword"), dropped);
        assertNull(svc.config(SITE, PLUGIN).get("smtpPassword"), "the plugin has no password to send anywhere");
        assertEquals("127.0.0.1", svc.config(SITE, PLUGIN).get("smtpHost"));
    }

    @Test
    void eachBoundSettingCountsAndRetypingKeepsTheNewSecret(@TempDir Path external) {
        SitePluginConfigService svc = site(external);
        Map<String, String> base = form("mail.example.com", "587", "u", "pw-1", "a@example.com");
        svc.save(SITE, PLUGIN, BOUND, base);

        Map<String, String> port = form("mail.example.com", "2525", "u", "", "a@example.com");
        assertEquals(List.of("smtpPassword"), svc.save(SITE, PLUGIN, BOUND, port), "port");
        svc.save(SITE, PLUGIN, BOUND, base);
        Map<String, String> user = form("mail.example.com", "587", "other", "", "a@example.com");
        assertEquals(List.of("smtpPassword"), svc.save(SITE, PLUGIN, BOUND, user), "username");
        svc.save(SITE, PLUGIN, BOUND, base);
        Map<String, String> cleared = form("", "587", "u", "", "a@example.com");
        assertEquals(List.of("smtpPassword"), svc.save(SITE, PLUGIN, BOUND, cleared), "host cleared to default");

        // A new host WITH the password typed again: the new value is stored, nothing reported dropped.
        assertEquals(List.of(), svc.save(SITE, PLUGIN, BOUND,
                form("smtp.other.example", "465", "u2", "pw-2", "a@example.com")));
        assertEquals("ENC(2-wp)", svc.config(SITE, PLUGIN).get("smtpPassword"));
    }

    @Test
    void anUnrelatedOrIdenticalResaveKeepsTheStoredSecret(@TempDir Path external) {
        SitePluginConfigService svc = site(external);
        svc.save(SITE, PLUGIN, BOUND, form("mail.example.com", "587", "u", "pw-1", "a@example.com"));
        // Unbound setting changed, bound ones identical (whitespace and a default-valued port included).
        assertEquals(List.of(), svc.save(SITE, PLUGIN, BOUND,
                form(" mail.example.com ", "587", "u", "", "b@example.com")));
        assertEquals(List.of(), svc.save(SITE, PLUGIN, BOUND,
                form("mail.example.com", "587", "u", null, "b@example.com")));
        assertEquals("ENC(1-wp)", svc.config(SITE, PLUGIN).get("smtpPassword"));
    }

    @Test
    void aHandEditedPortStoredAsTextIsNotAChange(@TempDir Path external) throws IOException {
        SitePluginConfigService svc = site(external);
        Path file = external.resolve("sites").resolve(SITE).resolve("plugins").resolve(PLUGIN + ".yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "smtpHost: mail.example.com\nsmtpPort: '2525'\nsmtpUsername: u\n"
                + "smtpPassword: ENC(1-wp)\nsmtpAuth: 'true'\n");
        assertEquals(List.of(), svc.save(SITE, PLUGIN, BOUND,
                form("mail.example.com", "2525", "u", "", "a@example.com")));
        assertEquals("ENC(1-wp)", svc.config(SITE, PLUGIN).get("smtpPassword"));
    }

    @Test
    void withoutABindingABlankSecretIsKeptAsBefore(@TempDir Path external) {
        List<PluginSettingDef> unbound = smtpSchema(null);
        SitePluginConfigService svc = site(external);
        svc.save(SITE, PLUGIN, unbound, form("mail.example.com", "587", "u", "pw-1", "a@example.com"));
        assertEquals(List.of(), svc.save(SITE, PLUGIN, unbound, form("127.0.0.1", "2525", "u", "", "a@example.com")));
        assertEquals("ENC(1-wp)", svc.config(SITE, PLUGIN).get("smtpPassword"),
                "a plugin that declares no binding keeps today's behaviour");
    }

    @Test
    void anUnknownBoundKeyNeverMatchesAndNothingStoredMeansNothingToReport(@TempDir Path external) {
        SitePluginConfigService svc = site(external);
        List<PluginSettingDef> typo = smtpSchema(List.of("smtpHots"));
        svc.save(SITE, PLUGIN, typo, form("mail.example.com", "587", "u", "pw-1", "a@example.com"));
        assertEquals(List.of(), svc.save(SITE, PLUGIN, typo, form("127.0.0.1", "587", "u", "", "a@example.com")));

        SitePluginConfigService fresh = site(external.resolve("other"));
        assertEquals(List.of(), fresh.save(SITE, PLUGIN, BOUND, form("127.0.0.1", "587", "u", "", "a@example.com")),
                "no secret was stored, so none is dropped");
    }

    // --- app level -------------------------------------------------------------------------------------

    @Test
    void theAppLevelSaveFollowsTheSameRule(@TempDir Path external) throws IOException {
        PluginSettingsService svc = app(external);
        svc.save(PLUGIN, BOUND, form("mail.example.com", "587", "u", "pw-1", "a@example.com"));
        Path file = external.resolve("plugin-settings").resolve(PLUGIN + ".yml");
        assertTrue(Files.readString(file).contains("ENC(1-wp)"));

        assertEquals(List.of(), svc.save(PLUGIN, BOUND, form("mail.example.com", "587", "u", "", "b@example.com")));
        assertTrue(Files.readString(file).contains("ENC(1-wp)"), "unrelated change keeps it");

        assertEquals(List.of("smtpPassword"),
                svc.save(PLUGIN, BOUND, form("evil.example", "587", "u", "", "b@example.com")));
        assertFalse(Files.readString(file).contains("ENC("), "repointed host drops it");
    }

    // --- descriptor parsing ----------------------------------------------------------------------------

    private static PluginSettingDef parsedSecret(String boundYaml) {
        String yaml = "id: mailer\nname: Mailer\nversion: 1.0.0\nsiteSettings:\n"
                + "  - { key: smtpHost, type: string }\n"
                + "  - key: smtpPassword\n    type: secret\n" + boundYaml;
        PluginDescriptor descriptor = PluginDescriptor.read(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        descriptor.validate();
        return descriptor.siteSettings().get(1);
    }

    @Test
    void theDescriptorKeyIsReadLeniently() {
        assertEquals(List.of("smtpHost", "smtpPort"), parsedSecret("    secretBoundTo: [smtpHost, smtpPort]\n")
                .secretBoundTo());
        assertEquals(List.of("smtpHost"), parsedSecret("    secretBoundTo: smtpHost\n").secretBoundTo());
        assertEquals(List.of("smtpHost", "smtpPort"), parsedSecret("    secretBoundTo: 'smtpHost, smtpPort,'\n")
                .secretBoundTo());
        assertEquals(List.of("a", "b"), parsedSecret("    secretBoundTo: [' a ', '', b, a, {x: 1}]\n")
                .secretBoundTo(), "trimmed, blanks/maps dropped, de-duplicated");
        assertEquals(List.of(), parsedSecret("    secretBoundTo: {smtpHost: true}\n").secretBoundTo(),
                "a malformed value reads as empty and the plugin still loads");
        assertEquals(List.of(), parsedSecret("    secretBoundTo:\n").secretBoundTo());
        assertEquals(List.of(), parsedSecret("").secretBoundTo(), "absent = empty");
    }

    @Test
    void aBindingOnANonSecretMeansNothing() {
        PluginSettingDef host = new PluginSettingDef("smtpHost", "string", null, "l", null, null, null, null, null,
                null, null, null, List.of("smtpPort"));
        assertEquals(List.of("smtpPort"), host.secretBoundTo());
        assertEquals(List.of(), host.secretBindings());
    }

    // --- an unknown binding is warned about at descriptor validation ----------------------------

    /** Validates {@code yaml} and returns the WARN lines {@link PluginDescriptor} logged while doing it. */
    private static List<String> validationWarnings(String yaml) {
        Logger logger = (Logger) LoggerFactory.getLogger(PluginDescriptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            PluginDescriptor.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).validate();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static final String HEAD = "id: mailer\nname: Mailer\nversion: 1.0.0\n";

    @Test
    void aBindingToAnUndeclaredKeyIsWarnedNamingPluginFieldAndKeyAndThePluginStillValidates() {
        List<String> warnings = validationWarnings(HEAD + "siteSettings:\n"
                + "  - { key: smtpHost, type: string }\n"
                + "  - { key: smtpPassword, type: secret, secretBoundTo: [smtpHost, smtpHots] }\n");
        assertEquals(1, warnings.size(), warnings.toString());
        String w = warnings.get(0);
        assertTrue(w.contains("plugin 'mailer'") && w.contains("siteSettings") && w.contains("'smtpPassword'")
                && w.contains("'smtpHots'"), w);
        assertFalse(w.contains("'smtpHost'"), "the declared binding is not reported");
    }

    @Test
    void declaredBindingsWarnNothing() {
        assertEquals(List.of(), validationWarnings(HEAD + "siteSettings:\n"
                + "  - { key: smtpHost, type: string }\n  - { key: smtpPort, type: int }\n"
                + "  - { key: smtpPassword, type: secret, secretBoundTo: [smtpHost, smtpPort] }\n"
                + "settings:\n  - { key: apiHost, type: string }\n"
                + "  - { key: apiKey, type: secret, secretBoundTo: apiHost }\n"));
    }

    /** The binding is per schema: a key of the OTHER schema is as unknown as a typo; so is the secret itself. */
    @Test
    void aKeyOfAnotherSchemaOrTheSecretItselfIsWarnedInEverySchema() {
        List<String> warnings = validationWarnings(HEAD
                + "settings:\n  - { key: apiKey, type: secret, secretBoundTo: [smtpHost] }\n"
                + "siteSettings:\n  - { key: smtpHost, type: string }\n"
                + "  - { key: smtpPassword, type: secret, secretBoundTo: [smtpPassword] }\n"
                + "widgetSettings:\n  map:\n    - { key: token, type: secret, secretBoundTo: [zoom] }\n");
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("settings setting 'apiKey'") && warnings.get(0).contains("'smtpHost'"),
                warnings.get(0));
        assertTrue(warnings.get(1).contains("siteSettings setting 'smtpPassword'"), warnings.get(1));
        assertTrue(warnings.get(2).contains("widgetSettings.map setting 'token'") && warnings.get(2).contains("'zoom'"),
                warnings.get(2));
    }

    /** secretBoundTo on a non-secret means nothing (see above), so nothing is warned either. */
    @Test
    void aBindingOnANonSecretIsNotWarned() {
        assertEquals(List.of(), validationWarnings(HEAD + "siteSettings:\n"
                + "  - { key: smtpHost, type: string, secretBoundTo: [nowhere] }\n"));
    }
}
