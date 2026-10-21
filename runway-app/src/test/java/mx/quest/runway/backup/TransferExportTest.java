package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The <b>transfer</b> archive: the same site with everything that must not leave the building taken out.
 *
 * <h2>Why this exists</h2>
 * A backup has to be complete or it is not a backup — sub-users and their password hashes, per-plugin
 * credentials, the hostnames the site answers on. A hand-over has the opposite requirement, and until now the
 * two were the <b>same click</b>: anyone who attached a site backup to a bug report, handed one to a
 * contractor, or published one as an example was shipping live secrets and had no way to tell.
 *
 * <p>Verified against the archive BYTES rather than against the sanitiser's inputs. A sanitiser is trusted the
 * moment it exists, so the only assertion worth making is the one a recipient could make: open the file, and
 * look for the secret.
 */
class TransferExportTest {

    private static final String RECAPTCHA_SECRET = "ENC(AbCdEf0123456789)";
    private static final String RECAPTCHA_SITE_KEY = "6LcRealLookingSiteKey";

    @TempDir
    Path external;
    private StorageLayout layout;
    private Path siteDir;
    private BackupService svc;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        props.setBackups(external.resolve("backups"));
        layout = StorageLayout.from(props);
        siteDir = layout.siteDir("acme");

        write(siteDir.resolve("site.yml"),
                "id: acme\nname: Acme\nhosts:\n  - acme.example-real.test\n  - www.acme.example-real.test\n"
                + "baseUrl: https://acme.example-real.test\n");
        write(siteDir.resolve("templates/_master.html"), "MASTER");
        write(siteDir.resolve("templates/index.html"), "<p>public content</p>");
        write(siteDir.resolve("users.yml"),
                "- username: editor\n  passwordHash: $argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA\n");
        write(siteDir.resolve("permissions.yml"), "users:\n  editor:\n    grant: [EDIT_PAGES]\n");
        write(siteDir.resolve("bandwidth.csv"), "2026-08-01,12345\n");
        // The FLAT shape the generic per-site settings form writes (dotted top-level keys).
        write(siteDir.resolve("plugins/someplugin.yml"),
                "captcha.provider: recaptcha\ncaptcha.site-key: " + RECAPTCHA_SITE_KEY + "\n"
                + "captcha.secret-key: " + RECAPTCHA_SECRET + "\nlogin.max-attempts: 5\n");
        // The NESTED shape the bundled protection plugin actually writes — copied from a real production
        // export, not invented here. Getting this wrong is what made the first version of these tests
        // vacuous: they only ever exercised the one layout the sanitiser happened to handle.
        write(siteDir.resolve("plugins/protection.yml"),
                "honeypotEnabled: true\ncaptcha:\n  enabled: true\n  provider: \"recaptcha-v2\"\n"
                + "  siteKey: \"" + RECAPTCHA_SITE_KEY + "\"\n"
                + "  secretKey: \"" + RECAPTCHA_SECRET + "\"\n  scoreThreshold: 0.5\n");

        svc = new BackupService(layout, new SiteStore(layout, new YamlStore()),
                new BackupParticipantRegistry(List.of()), event -> { }, new BackupProperties(),
                pluginDeclaring("someplugin", "captcha.secret-key", "captcha.site-key"));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * A running-plugin set with ONE plugin declaring the given keys as secrets.
     *
     * <p>Note what is deliberately absent: no descriptor for {@code protection}. That mirrors the product —
     * the bundled protection plugin declares no {@code siteSettings} block at all and persists its own nested
     * record — so every assertion about {@code protection.yml} below is exercising the schema-less path,
     * which is the one that leaked.
     */
    private RunningPlugins pluginDeclaring(String pluginId, String... secretKeys) {
        List<PluginSettingDef> siteSettings = java.util.Arrays.stream(secretKeys)
                .map(key -> new PluginSettingDef(key, "secret", null, null, null))
                .toList();
        PluginDescriptor descriptor = new PluginDescriptor(pluginId, pluginId, "1.0.0", null,
                true, false, List.of(), List.of(), List.of(), List.of(), siteSettings, Map.of(),
                List.of(), List.of(), null);
        LoadedPlugin plugin = new LoadedPlugin(descriptor, external.resolve("plugins/" + pluginId));
        return () -> List.of(plugin);
    }

    /** Every entry of an archive, by name. */
    private static Map<String, String> entries(byte[] archive) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                out.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private byte[] archive(BackupService.BackupPurpose purpose) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.backup("acme", out, purpose);
        return out.toByteArray();
    }

    /**
     * The assertion a recipient could make: unpack the archive and search every file for the secret.
     *
     * <p><b>Over the decompressed entries, not the archive bytes.</b> Scanning the raw {@code .zip} for a
     * plaintext needle is a test that cannot fail: ZIP deflates its entries, so the secret is not literally
     * present even when it is fully there. That version of this test passed with the sanitiser switched off —
     * caught by disabling the sanitiser and seeing which tests noticed, which is the only reliable way to
     * find a check that is quietly asserting nothing.
     */
    @Test
    void noSecretSurvivesAnywhereInATransferArchive() throws IOException {
        Map<String, String> files = entries(archive(BackupService.BackupPurpose.TRANSFER));

        for (Map.Entry<String, String> file : files.entrySet()) {
            String where = file.getKey();
            assertFalse(file.getValue().contains(RECAPTCHA_SECRET), where + " carries the encrypted secret");
            assertFalse(file.getValue().contains("argon2id"), where + " carries a tenant password hash");
        }
        assertFalse(files.isEmpty(), "an empty archive would pass every assertion above");

        // The DECLARED-secret net, checked where it applies: the flat plugin declares its site key a secret,
        // so that plugin's copy is blanked. The bundled protection plugin declares nothing and its site key
        // is public by design, so it survives — see aPublicValueNamedKeyIsNotBlanked for why that is right,
        // and the guide for what a recipient must therefore replace.
        assertFalse(files.get("site/plugins/someplugin.yml").contains(RECAPTCHA_SITE_KEY),
                "a DECLARED secret must be blanked whatever it is named");
    }

    /** …and the same search over an ordinary backup finds them, so the test above can actually fail. */
    @Test
    void anOrdinaryBackupStillCarriesEverything() throws IOException {
        Map<String, String> files = entries(archive(BackupService.BackupPurpose.RESTORE));

        assertTrue(files.get("site/plugins/protection.yml").contains(RECAPTCHA_SECRET),
                "a backup that dropped this would not restore the site");
        assertTrue(files.get("site/plugins/someplugin.yml").contains(RECAPTCHA_SECRET),
                "…in both config shapes");
        assertTrue(files.containsKey("site/users.yml"), "sub-users are part of the site");
        assertTrue(files.get("site/site.yml").contains("acme.example-real.test"), "so are its hostnames");
    }

    /** Accounts and grants are withheld: they are the recipient's biggest exposure and no use to them. */
    @Test
    void aTransferArchiveOmitsAccountsGrantsAndTraffic() throws IOException {
        Map<String, String> files = entries(archive(BackupService.BackupPurpose.TRANSFER));

        assertFalse(files.containsKey("site/users.yml"));
        assertFalse(files.containsKey("site/permissions.yml"));
        assertFalse(files.containsKey("site/bandwidth.csv"), "traffic history is the origin's business");
    }

    /** Content and presentation must survive, or the archive is not worth handing over. */
    @Test
    void aTransferArchiveKeepsContentPresentationAndNonSecretSettings() throws IOException {
        Map<String, String> files = entries(archive(BackupService.BackupPurpose.TRANSFER));

        assertEquals("<p>public content</p>", files.get("site/templates/index.html"));
        assertEquals("MASTER", files.get("site/templates/_master.html"));
        assertTrue(files.get("site/plugins/someplugin.yml").contains("login.max-attempts"),
                "a non-secret setting is exactly what the recipient wants to inherit");
        assertTrue(files.get("site/plugins/protection.yml").contains("honeypotEnabled"),
                "…and a nested plugin's non-secret settings survive too");
    }

    /**
     * Secret keys are blanked, not deleted.
     *
     * <p>An absent key reads as a setting that does not exist; a blank one shows the recipient what has to be
     * filled in before the feature works.
     */
    @Test
    void secretsAreBlankedRatherThanRemoved() throws IOException {
        String flat = entries(archive(BackupService.BackupPurpose.TRANSFER))
                .get("site/plugins/someplugin.yml");

        assertTrue(flat.contains("captcha.secret-key"), "the key stays so its absence is visible: " + flat);
        assertTrue(flat.contains("captcha.site-key"));
    }

    /**
     * Hostnames are replaced with a reserved placeholder derived from the site id.
     *
     * <p>{@code example.com} is reserved for exactly this (RFC 2606), so a placeholder left in place by
     * accident resolves nowhere rather than at somebody else's real domain — and deriving it from the id keeps
     * two sites from the same installation from colliding when both are imported side by side.
     */
    @Test
    void hostnamesAreNeutralisedToAReservedPlaceholder() throws IOException {
        String site = entries(archive(BackupService.BackupPurpose.TRANSFER)).get("site/site.yml");

        assertFalse(site.contains("example-real.test"), "the origin's hostnames travelled: " + site);
        assertTrue(site.contains("acme.example.com"), site);
        assertTrue(site.contains("name: Acme") || site.contains("name: \"Acme\""),
                "the site's own identity is not a secret: " + site);
    }

    /**
     * With no plugin schema available, an {@code ENC(...)} value is still blanked.
     *
     * <p>The safety net for the case the declaration cannot cover — the plugin is not installed here, or was
     * renamed. An encrypted value is self-identifying, so a stored secret does not leave even with no schema
     * at all. A sanitiser that is only as good as its inputs is not one you can hand someone.
     */
    @Test
    void anEncryptedValueIsBlankedEvenWithNoPluginSchema() throws IOException {
        BackupService bare = new BackupService(layout, new SiteStore(layout, new YamlStore()),
                new BackupParticipantRegistry(List.of()), event -> { }, new BackupProperties(),
                (RunningPlugins) null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bare.backup("acme", out, BackupService.BackupPurpose.TRANSFER);

        String flat = entries(out.toByteArray()).get("site/plugins/someplugin.yml");
        assertFalse(flat.contains(RECAPTCHA_SECRET), "an ENC(...) value must never travel: " + flat);
    }

    /** The archive says which kind it is, so a restore (or a reader) is not left guessing. */
    @Test
    void theManifestRecordsWhichKindOfArchiveThisIs() throws IOException {
        assertTrue(entries(archive(BackupService.BackupPurpose.TRANSFER)).get("manifest.yml")
                .contains("TRANSFER"));
        assertTrue(entries(archive(BackupService.BackupPurpose.RESTORE)).get("manifest.yml")
                .contains("RESTORE"));
    }

    /** An unmarked archive reads as complete — never as sanitised, which would be the dangerous default. */
    @Test
    void anArchiveWithNoPurposeReadsAsAFullBackup() {
        BackupManifest old = new BackupManifest("acme", "0.3.4", "2026-01-01T00:00:00Z", List.of());

        assertFalse(old.isTransfer(), "an archive from before the distinction existed is a full backup");
        assertEquals("RESTORE", old.purpose());
    }

    /** A transfer archive still restores — it is the ordinary format, just with less in it. */
    @Test
    void aTransferArchiveRestoresIntoARenderableSite() throws IOException {
        byte[] transfer = archive(BackupService.BackupPurpose.TRANSFER);
        write(siteDir.resolve("templates/index.html"), "CLOBBERED");

        svc.restore("acme", new ByteArrayInputStream(transfer));

        assertEquals("<p>public content</p>",
                Files.readString(siteDir.resolve("templates/index.html"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(siteDir.resolve("users.yml")),
                "the accounts were never in the archive, so the restore has none to put back");
    }

    // ---- the nested shape: the one the bundled protection plugin actually writes --------------------

    /**
     * The defect these tests were written to prevent and did not: a plugin that stores NESTED config, with
     * no declared schema, had its secret copied into the transfer archive verbatim.
     *
     * <p>{@code protection} is not a hypothetical — it is the bundled, always-installed plugin, it declares
     * no {@code siteSettings} block at all, and it persists {@code captcha:} &rarr; {@code secretKey: ENC(...)}
     * one level down. The original sanitiser walked only top-level entries, so it saw {@code honeypotEnabled}
     * (a Boolean) and {@code captcha} (a Map), matched neither, and returned "nothing to change".
     *
     * <p>The fixture above is the shape taken from a real production export, not one invented for the test.
     * That is the whole lesson: the first version of this suite passed because its fixture was written by the
     * same hand, and in the same shape, as the code it was checking.
     */
    @Test
    void aNestedPluginConfigHasItsSecretBlankedToo() throws IOException {
        String protection = entries(archive(BackupService.BackupPurpose.TRANSFER))
                .get("site/plugins/protection.yml");

        assertFalse(protection.contains(RECAPTCHA_SECRET),
                "the bundled plugin's encrypted CAPTCHA secret travelled: " + protection);
        assertTrue(protection.contains("secretKey"), "the key stays so the recipient sees what to fill in");
        assertTrue(protection.contains("honeypotEnabled: true"), "non-secret settings still travel");
    }

    /**
     * A secret stored in PLAINTEXT, in a plugin that declares nothing, is still caught.
     *
     * <p>Neither the declaration net nor the {@code ENC(} net can see this one, so it is covered by the key
     * NAME. The word list is short and unambiguous on purpose — see the next test for the other half.
     */
    @Test
    void aPlaintextSecretInAnUndeclaredPluginIsCaughtByItsName() throws IOException {
        write(siteDir.resolve("plugins/mailer.yml"),
                "smtp:\n  host: mail.example-real.test\n  username: postmaster\n"
                + "  password: hunter2-in-the-clear\n  apiToken: tok_live_abcdef\n");

        String mailer = entries(archive(BackupService.BackupPurpose.TRANSFER)).get("site/plugins/mailer.yml");

        assertFalse(mailer.contains("hunter2-in-the-clear"), mailer);
        assertFalse(mailer.contains("tok_live_abcdef"), mailer);
        assertTrue(mailer.contains("postmaster"), "a username is not a credential and must survive");
        assertTrue(mailer.contains("mail.example-real.test"), "…nor is a hostname inside a plugin's config");
    }

    /**
     * A public value whose name merely contains "key" is NOT blanked.
     *
     * <p>The other half of the name heuristic, and the reason {@code key} is not in the word list: a
     * reCAPTCHA <em>site</em> key is public by design — it is embedded in the page markup — and blanking a
     * working setting is its own kind of harm. A sanitiser that silently breaks the recipient's site is not
     * one anybody keeps using.
     */
    @Test
    void aPublicValueNamedKeyIsNotBlanked() throws IOException {
        String protection = entries(archive(BackupService.BackupPurpose.TRANSFER))
                .get("site/plugins/protection.yml");

        assertTrue(protection.contains(RECAPTCHA_SITE_KEY),
                "siteKey is public (it ships in the page); blanking it would break the recipient for nothing: "
                        + protection);
    }

    /** A secret nested inside a LIST of maps is reached as well. */
    @Test
    void aSecretInsideAListOfMapsIsAlsoBlanked() throws IOException {
        write(siteDir.resolve("plugins/webhooks.yml"),
                "endpoints:\n  - name: billing\n    url: https://example-real.test/hook\n"
                + "    secret: whsec_do_not_ship\n  - name: audit\n    url: https://example-real.test/a\n");

        String hooks = entries(archive(BackupService.BackupPurpose.TRANSFER)).get("site/plugins/webhooks.yml");

        assertFalse(hooks.contains("whsec_do_not_ship"), hooks);
        assertTrue(hooks.contains("billing"), "the rest of the entry survives");
    }

    /**
     * A transfer copy carried {@code templates/.pending/<slug>/review.yml} — the
     * submitter's and returner's account names (often email addresses) and the reviewer's note — although it drops
     * {@code users.yml}. The whole pending subtree (unpublished drafts too) now stays home, and the manifest says
     * so; a full backup keeps all of it, so a restore brings the review queue back.
     */
    @Test
    void aTransferCopyLeavesOutPendingEditorialWorkAndSaysSo() throws IOException {
        write(siteDir.resolve("templates/.pending/about/review.yml"),
                "state: RETURNED\nsubmittedBy: \"ed@example.org\"\nreturnedBy: \"boss@example.org\"\n"
                + "note: \"Not before the merger is announced\"\n");
        write(siteDir.resolve("templates/.pending/about/about.html"), "<p>UNPUBLISHED merger draft</p>");
        write(siteDir.resolve("templates/.pending/about/about.meta.yml"), "title: About\n");
        write(siteDir.resolve("templates/pending-notes.html"), "<p>a live page whose NAME starts like it</p>");

        Map<String, String> transfer = entries(archive(BackupService.BackupPurpose.TRANSFER));
        for (Map.Entry<String, String> file : transfer.entrySet()) {
            assertFalse(file.getKey().contains(".pending"), "carried " + file.getKey());
            assertFalse(file.getValue().contains("ed@example.org") || file.getValue().contains("boss@example.org")
                    || file.getValue().contains("merger"), file.getKey() + " carries pending work");
        }
        assertTrue(transfer.containsKey("site/templates/pending-notes.html"), "only the .pending subtree is left out");
        assertTrue(transfer.containsKey("site/templates/index.html"), "published content still travels");
        assertTrue(transfer.get("manifest.yml").contains("templates/.pending/"), transfer.get("manifest.yml"));
        assertEquals(List.of("templates/.pending/"), svc.transferOmissions("acme"),
                "the backup screen lists it before the copy is made");

        Map<String, String> full = entries(archive(BackupService.BackupPurpose.RESTORE));
        assertTrue(full.get("site/templates/.pending/about/review.yml").contains("ed@example.org"),
                "a full backup keeps the review queue");
        assertTrue(full.containsKey("site/templates/.pending/about/about.html"));
        assertFalse(full.get("manifest.yml").contains(".pending"), "nothing is omitted from a full backup");
    }

    @Test
    void withNoPendingWorkNothingExtraIsListed() throws IOException {
        assertEquals(List.of(), svc.transferOmissions("acme"));
        assertFalse(entries(archive(BackupService.BackupPurpose.TRANSFER)).get("manifest.yml").contains(".pending"));
    }
}
