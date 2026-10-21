package mx.quest.runway.wizard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.dataformat.yaml.YAMLMapper;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.TenantProvisioning;
import mx.quest.runway.security.User;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteLifecycleService;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Performs first-run provisioning: it creates the pinned application superadmin, records the pin in the
 * external {@code application.yml}, seeds a first site, and triggers the supervised relaunch into ACTIVE
 * mode. Present only in WIZARD mode.
 *
 * <p>The superadmin's principal id is the fixed {@value #APP_MASTER_ID} (there is exactly one), which
 * becomes the {@code runway.security.app-master-id} pin. The store write happens while the pin is still
 * unset in the running JVM, so {@link AppUserStore}'s master guard does not yet apply; on relaunch the
 * pin is set and the master becomes immutable-from-within-the-realm.
 */
@Service
@Conditional(WizardModeCondition.class)
public class WizardService {

    /** The fixed principal id of the single application superadmin (and the pin value). */
    public static final String APP_MASTER_ID = "app-master";


    private static final Logger log = LoggerFactory.getLogger(WizardService.class);

    private final PasswordEncoder passwordEncoder;
    private final AppUserStore appUsers;
    private final SiteLifecycleService sites;
    private final SiteStore siteStore;
    private final TenantProvisioning provisioning;
    private final PlatformSettingsStore platform;
    private final StorageLayout layout;
    private final Restarter restarter;

    public WizardService(PasswordEncoder passwordEncoder, AppUserStore appUsers, SiteLifecycleService sites,
                         SiteStore siteStore, TenantProvisioning provisioning, PlatformSettingsStore platform,
                         StorageLayout layout, Restarter restarter) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.appUsers = Objects.requireNonNull(appUsers, "appUsers");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.siteStore = Objects.requireNonNull(siteStore, "siteStore");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.restarter = Objects.requireNonNull(restarter, "restarter");
    }

    /**
     * Provisions the application superadmin AND the default site's tenant superadmin, seeds and marks the
     * default site, writes the external config with the pin, and relaunches into ACTIVE mode.
     *
     * <p>The two credentials are for the <b>two never-crossing realms</b>: the application superadmin runs the
     * platform; the site administrator manages the seeded default site (a separate {@link Realm#TENANT}
     * identity — the app superadmin can never operate inside a tenant). The seeded site is also marked the
     * catch-all {@linkplain PlatformSettingsStore default site} so unbound hosts resolve to it.
     *
     * @param username          the application-superadmin login name
     * @param rawPassword        the application-superadmin password (hashed here, never stored raw)
     * @param displayName        a human label for the application superadmin
     * @param recoveryEmail      an out-of-band recovery address for the application superadmin
     * @param siteAdminUsername the default site's tenant-superadmin login name
     * @param siteAdminPassword the default site's tenant-superadmin password (hashed here)
     */

    /**
     * Completes first-run setup: seeds the default site from the starter template, provisions that site's tenant
     * superadmin with the supplied credentials, writes the application superadmin, and relaunches.
     *
     * <p>There is exactly <b>one</b> shape of default site. The wizard previously offered to fill it with a
     * bundled multi-page demo instead; that choice is gone along with the bundled demo trees, which are now
     * ordinary sites maintained outside the product. Removing the branch also removes a first-run decision an
     * operator had no basis to make — the starter template is a real two-page site, so "blank versus demo" was
     * never the meaningful choice it looked like.
     *
     * <p>The site and its tenant superadmin are created <b>before</b> the application superadmin is committed, so
     * any failure rolls back to a clean slate and the operator can simply retry.
     */
    public void completeSetup(String username, String rawPassword, String displayName, String recoveryEmail,
                              String siteAdminUsername, String siteAdminPassword) {
        completeSetup(username, rawPassword, displayName, recoveryEmail, siteAdminUsername, siteAdminPassword, "");
    }

    /**
     * {@link #completeSetup(String, String, String, String, String, String)} with the default site
     * administrator's recovery email ({@code null}/blank ⇒ none), so that account can use "Forgot your
     * password?" from the start.
     */
    public void completeSetup(String username, String rawPassword, String displayName, String recoveryEmail,
                              String siteAdminUsername, String siteAdminPassword, String siteAdminEmail) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("username and password are required");
        }
        if (siteAdminUsername == null || siteAdminUsername.isBlank()
                || siteAdminPassword == null || siteAdminPassword.isBlank()) {
            throw new IllegalArgumentException("the default site's administrator username and password are required");
        }

        // Seed (or install) the default site AND its tenant superadmin BEFORE committing the application
        // superadmin, so any failure rolls back to a clean slate — no half-provisioned site, no application
        // master, no pin — and the operator can simply retry.
        String siteId = seedDefaultSite(siteAdminUsername.trim(), siteAdminPassword,
                siteAdminEmail == null ? "" : siteAdminEmail.trim());

        // Commit: the application superadmin, the default-site marker, the pin, then the supervised relaunch.
        // The master store write happens while the pin is still unset in the running JVM, so AppUserStore's
        // master guard does not yet apply.
        User master = new User(APP_MASTER_ID, username.trim(), passwordEncoder.encode(rawPassword),
                displayName, recoveryEmail, true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of());
        appUsers.save(master);
        platform.update(current -> current.withDefaultSiteId(siteId));  // demo install already sets it; a harmless re-set
        writeExternalConfig(APP_MASTER_ID);
        restarter.restart();
    }

    /**
     * Seeds the default site from the starter template catalog and provisions its tenant superadmin.
     * A clean slate is required: {@link SiteLifecycleService#seedDefaultSite()} returns empty when a site already
     * exists, which in WIZARD mode signals an inconsistent state (e.g. {@code application.yml} lost while
     * {@code sites/} survived) — fail loudly rather than silently completing setup with a superadmin-less site.
     * A provisioning failure removes the just-seeded site so the retry starts clean.
     *
     * @return the seeded site's id
     */
    private String seedDefaultSite(String siteAdminUsername, String siteAdminPassword, String siteAdminEmail) {
        Site site = sites.seedDefaultSite().orElseThrow(() -> new IllegalStateException(
                "cannot complete first-run setup: a site already exists — resolve the inconsistent state first"));
        try {
            provisioning.provisionMaster(UUID.randomUUID().toString(), siteAdminUsername,
                    passwordEncoder.encode(siteAdminPassword), site.id(), site.name(), siteAdminEmail);
        } catch (RuntimeException provisioningFailed) {
            sites.delete(site.id());
            throw provisioningFailed;
        }
        return site.id();
    }

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(tools.jackson.dataformat.yaml.YAMLWriteFeature.MINIMIZE_QUOTES)          // human-friendly output
            .disable(tools.jackson.dataformat.yaml.YAMLWriteFeature.WRITE_DOC_START_MARKER)  // no leading '---'
            .build();

    /**
     * Records the app-master pin in the external {@code application.yml}, mode 600 — <b>merging</b> into any
     * existing file rather than overwriting it, so operator/installer settings there (self-managed TLS, proxy
     * CIDRs, {@code server.port}, …) survive first-run setup. A pre-existing file that is not valid YAML is
     * left untouched and the setup fails with a clear message, so a hand-edit is never silently destroyed.
     */
    private void writeExternalConfig(String pin) {
        Path file = layout.external().resolve("application.yml");
        Map<String, Object> root = loadExisting(file);
        childMap(childMap(root, "runway"), "security").put("app-master-id", pin);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, YAML.writeValueAsBytes(root));
            restrictToOwner(file);
        } catch (IOException e) {
            throw new UncheckedIOException("writing external application.yml", e);
        }
        log.info("Wrote external application.yml with the application-superadmin pin (existing config preserved).");
    }

    /** Parses an existing config to a mutable tree, or a fresh map when absent/empty; never clobbers bad YAML. */
    private static Map<String, Object> loadExisting(Path file) {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new LinkedHashMap<>();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = YAML.readValue(bytes, Map.class);
            return (parsed == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("the existing external application.yml is not valid YAML — fix it "
                    + "before completing first-run setup (" + file + "): " + e.getMessage(), e);
        }
    }

    /** Gets or creates a nested map child, failing loudly if the existing value is not a mapping. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing == null) {
            Map<String, Object> child = new LinkedHashMap<>();
            parent.put(key, child);
            return child;
        }
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("external application.yml: '" + key + "' is not a mapping — cannot merge "
                + "the application-superadmin pin; fix the file and retry setup");
    }

    /** chmod 600 where the filesystem supports POSIX permissions (a no-op elsewhere — it holds a secret). */
    private static void restrictToOwner(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }
}
