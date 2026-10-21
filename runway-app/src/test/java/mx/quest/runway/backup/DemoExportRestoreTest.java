package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restores the <b>real published demo-site exports</b> through the <b>real</b> {@link BackupService}.
 *
 * <h2>Why this exists</h2>
 * The retired demo sites were packaged as downloadable site backups, with the archive format built from
 * observation of a genuine console-produced backup. The packaging was verified structurally, but no restore
 * could be run without application-superadmin credentials. That left exactly one unverified claim, and it is the one
 * that matters most to
 * whoever downloads the file: <b>does it actually restore?</b>
 *
 * <p>Structural inspection cannot answer that. Only feeding the bytes to the real restore path can.
 *
 * <p>This test also independently re-checks the <b>sanitisation</b> claims against the shipped
 * bytes, because "we removed the secrets" is precisely the kind of assertion that should not be taken on
 * trust when the artifact is about to be published publicly — and a scanner used on them had once
 * silently checked nothing and reported clean.
 *
 * <h2>Conditional by design</h2>
 * The exports live outside the repository (they are large binaries). Their directory is named by the
 * {@code runway.demo-exports} system property, and this skips when it is unset or holds no export rather
 * than failing. A skipped test is honest; a test that cannot run and says
 * nothing is not, so the condition is named and the reason is visible in the report.
 */
class DemoExportRestoreTest {

    /** The directory holding the published exports, from {@code -Drunway.demo-exports=<dir>}; null when unset. */
    private static final Path EXPORTS = pathProperty("runway.demo-exports");

    /**
     * A host the exports must never point back at (the exporting installation's own domain), from
     * {@code -Drunway.demo-exports.forbidden-host=<host>}; null when unset, and the check is then skipped.
     */
    private static final String FORBIDDEN_HOST = stringProperty("runway.demo-exports.forbidden-host");

    private static String stringProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Path pathProperty(String name) {
        String value = stringProperty(name);
        return value == null ? null : Path.of(value);
    }

    static boolean exportsPresent() {
        return EXPORTS != null && hasExports(EXPORTS);
    }

    /**
     * Whether {@code dir} holds at least one published export.
     *
     * <p>Extracted from {@link #exportsPresent()} so the GATE itself can be tested against a temporary
     * directory. The previous "guard the guard" test compared the {@code EXPORTS} constant to a copy of its
     * own literal, which fails only if someone edits one and not the other — it could not detect the case its
     * own javadoc named.
     */
    static boolean hasExports(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".zip"));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static List<Path> listExports() {
        if (EXPORTS == null || !Files.isDirectory(EXPORTS)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(EXPORTS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A service over a throwaway external tree, with the target site pre-created (restore is a swap). */
    private record Fixture(BackupService service, StorageLayout layout) {
    }

    private static Fixture fixture(Path external, String siteId) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        props.setBackups(external.resolve("backups"));
        StorageLayout layout = StorageLayout.from(props);
        Path siteDir = layout.siteDir(siteId);
        Files.createDirectories(siteDir);
        // Restore is a destructive swap over an existing site, so stand up a minimal one to swap.
        Files.writeString(siteDir.resolve("site.yml"), "id: " + siteId + "\nname: Placeholder\n",
                StandardCharsets.UTF_8);
        BackupService service = new BackupService(layout, new SiteStore(layout, new YamlStore()),
                new BackupParticipantRegistry(List.of()), event -> { });
        return new Fixture(service, layout);
    }

    private static String siteIdOf(Path zip) {
        // runway-demo-<id>-<timestamp>.zip
        String name = zip.getFileName().toString();
        return name.replaceFirst("^runway-demo-", "").replaceFirst("-\\d{8}-\\d{6}Z\\.zip$", "");
    }

    /**
     * The claim the packaging could not verify: each published export restores through the real service and lands
     * a renderable site — a master template, a routable home page, and its own {@code site.yml}.
     */
    @Test
    @EnabledIf("exportsPresent")
    void everyPublishedExportRestoresIntoARenderableSite(@TempDir Path external) throws IOException {
        for (Path zip : listExports()) {
            String siteId = siteIdOf(zip);
            Path root = external.resolve(siteId);
            Fixture f = fixture(root, siteId);

            try (InputStream in = Files.newInputStream(zip)) {
                f.service().restore(siteId, in);          // the real path — throws if the archive is not valid
            }

            Path site = f.layout().siteDir(siteId);
            assertTrue(Files.isRegularFile(site.resolve("site.yml")), siteId + ": site.yml restored");
            assertTrue(Files.isRegularFile(site.resolve("templates/_master.html")),
                    siteId + ": a master template restored — without it the site cannot render");
            assertTrue(Files.isRegularFile(site.resolve("templates/index.meta.yml")),
                    siteId + ": a routable home page restored");
            assertFalse(Files.readString(site.resolve("site.yml"), StandardCharsets.UTF_8).contains("Placeholder"),
                    siteId + ": the restore actually replaced the pre-existing site, rather than merging into it");
        }
    }

    /**
     * Re-verifies the exports' sanitisation against the shipped bytes.
     *
     * <p>Not distrust — the same discipline applied to the scanner that checked them. These artifacts are about to be
     * published publicly, and the cost of being wrong is a leaked live credential, so the claim is worth
     * confirming from a second implementation.
     */
    @Test
    @EnabledIf("exportsPresent")
    void noPublishedExportCarriesASecretOrOurOwnHostnames(@TempDir Path external) throws IOException {
        for (Path zip : listExports()) {
            String siteId = siteIdOf(zip);
            Path root = external.resolve(siteId);
            Fixture f = fixture(root, siteId);
            try (InputStream in = Files.newInputStream(zip)) {
                f.service().restore(siteId, in);
            }
            Path site = f.layout().siteDir(siteId);

            try (Stream<Path> files = Files.walk(site)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String body = readTextOrEmpty(file);
                    String where = siteId + ":" + site.relativize(file);
                    assertFalse(body.contains("ENC("), where + " carries an encrypted-secret marker");
                    if (FORBIDDEN_HOST != null) {
                        assertFalse(body.contains(FORBIDDEN_HOST), where + " points back at " + FORBIDDEN_HOST);
                    }
                }
            }

            // The protection config must be present but neutered — absent would silently re-enable defaults.
            Path protection = site.resolve("plugins/protection.yml");
            if (Files.isRegularFile(protection)) {
                for (String line : Files.readAllLines(protection)) {
                    assertFalse(carriesPopulatedKey(line),
                            siteId + ": a populated credential survived sanitisation: " + line.trim());
                }
            }

            // Hostnames must be neutral, or a downloader's install answers on the exporting installation's domain.
            String siteYml = Files.readString(site.resolve("site.yml"), StandardCharsets.UTF_8);
            assertTrue(siteYml.contains("example.com") || !siteYml.contains("hosts"),
                    siteId + ": hosts were not neutralised: " + siteYml);
        }
    }

    /** Logs/stats are traffic data and must never travel in a published artifact. */
    @Test
    @EnabledIf("exportsPresent")
    void noPublishedExportCarriesTrafficData(@TempDir Path external) throws IOException {
        for (Path zip : listExports()) {
            String siteId = siteIdOf(zip);
            Path root = external.resolve(siteId);
            Fixture f = fixture(root, siteId);
            try (InputStream in = Files.newInputStream(zip)) {
                f.service().restore(siteId, in);
            }
            Path site = f.layout().siteDir(siteId);
            for (String dir : List.of("logs", "stats")) {
                Path d = site.resolve(dir);
                assertFalse(Files.isDirectory(d) && !isEmptyDir(d), siteId + ": " + dir + "/ travelled in the export");
            }
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    private static String readTextOrEmpty(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {                       // skip binaries (fonts, images) — they carry no config
                if (b == 0) {
                    return "";
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Whether a YAML line assigns a NON-EMPTY value to a credential field.
     *
     * <p>Written carefully because the naive version was wrong in the direction that matters: matching
     * {@code siteKey:\s*\S} treats {@code siteKey: ""} as populated, because the opening quote is a
     * non-space character. That produced a false accusation of a leaked credential against a correctly
     * sanitised artifact — the failure mode of a security check that cries wolf, which ends with the check
     * being ignored.
     */
    static boolean carriesPopulatedKey(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("#")) {
            return false;                                     // prose may name the fields while explaining them
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String key = trimmed.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT).replace("-", "");
        if (!key.equals("sitekey") && !key.equals("secretkey")) {
            return false;
        }
        String value = trimmed.substring(colon + 1).trim();
        if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
            value = value.substring(1, value.length() - 1);   // strip the quotes before judging emptiness
        }
        return !value.isBlank();
    }

    /**
     * The positive control: proves the credential check can actually produce a finding.
     *
     * <p>Added because a pre-publication scanner once spent its first hour checking
     * nothing and reporting clean — an unbalanced {@code grep -E} pattern errored, and the shell read that as
     * "not found". A security check that has never been seen to fail is indistinguishable from one that
     * cannot fail.
     */
    @Test
    void theCredentialCheckCanProduceAFinding() {
        assertTrue(carriesPopulatedKey("  siteKey: \"6LcSomeRealLookingKey\""), "must flag a populated key");
        assertTrue(carriesPopulatedKey("  secret-key: ENC(abc123)"), "must flag an encrypted secret");
        assertFalse(carriesPopulatedKey("  siteKey: \"\""), "an emptied key is sanitised, not a finding");
        assertFalse(carriesPopulatedKey("  secretKey: ''"), "…including single-quoted");
        assertFalse(carriesPopulatedKey("  siteKey:"), "…and a bare key with no value");
        assertFalse(carriesPopulatedKey("# set siteKey: your-key-here"), "prose explaining the field is not a leak");
    }
}
