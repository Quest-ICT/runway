package mx.quest.runway.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.TenantProvisioning;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteLifecycleService;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** First-run provisioning: the superadmin is created, the pin is written, a site is seeded, the relaunch fires. */
class WizardServiceTest {

    private static final class RecordingRestarter implements Restarter {
        boolean restarted;
        @Override public void restart() {
            restarted = true;
        }
    }

    /** The collaborators a test needs to drive and inspect the wizard. */
    private record Fixture(WizardService wizard, AppUserStore appUsers, SiteStore siteStore,
                           SiteLifecycleService sites, TenantMasterStore masters, PlatformSettingsStore platform,
                           RunwaySecurityProperties security, PasswordEncoder encoder, RecordingRestarter restarter) {
    }

    private static Fixture fixture(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        RunwaySecurityProperties security = new RunwaySecurityProperties(); // pin blank → WIZARD
        UserStore userStore = new UserStore(yaml);
        AppUserStore appUsers = new AppUserStore(layout, userStore, security);
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteLifecycleService sites = new SiteLifecycleService(siteStore, layout, event -> { }, new mx.quest.runway.i18n.SiteMessagesStore(layout));
        TenantMasterStore masters = new TenantMasterStore(layout, userStore);
        TenantProvisioning provisioning = new TenantProvisioning(masters);
        PlatformSettingsStore platform = new PlatformSettingsStore(layout, yaml);
        PasswordEncoder encoder = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
        RecordingRestarter restarter = new RecordingRestarter();
        WizardService wizard = new WizardService(encoder, appUsers, sites, siteStore, provisioning, platform,
                layout, restarter);
        return new Fixture(wizard, appUsers, siteStore, sites, masters, platform, security, encoder, restarter);
    }

    @Test
    void completeSetupProvisionsTheMasterWritesThePinAndRestarts(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        AppUserStore appUsers = f.appUsers();
        SiteStore siteStore = f.siteStore();
        TenantMasterStore masters = f.masters();
        PlatformSettingsStore platform = f.platform();
        PasswordEncoder encoder = f.encoder();
        RecordingRestarter restarter = f.restarter();

        f.wizard().completeSetup("root", "Sup3r-Secret-9", "Root", "r@x", "site-admin", "Site-Secret-9");

        // The pinned application master is provisioned with a hashed password.
        User master = appUsers.findByUsername("root").orElseThrow();
        assertEquals(WizardService.APP_MASTER_ID, master.principalId());
        assertTrue(master.master());
        assertTrue(master.hasRole(Roles.SYSTEM_SUPERADMIN));
        assertTrue(encoder.matches("Sup3r-Secret-9", master.passwordHash()));

        // The external application.yml carries the pin (so the relaunch comes up ACTIVE).
        String config = Files.readString(tmp.resolve("application.yml"), StandardCharsets.UTF_8);
        assertTrue(config.contains("app-master-id: " + WizardService.APP_MASTER_ID), config);

        assertFalse(siteStore.findAll().isEmpty(), "a first site is seeded");
        assertTrue(restarter.restarted, "the supervised relaunch is triggered");

        // The seeded default site ALWAYS has its tenant superadmin (a separate TENANT identity).
        User siteAdmin = masters.findByUsername("site-admin").orElseThrow();
        assertTrue(siteAdmin.hasRole(Roles.SUPERADMIN), "the site admin is a tenant SUPERADMIN");
        assertTrue(siteAdmin.assignedTo("default"), "assigned to the seeded default site");
        assertFalse(masters.assignedTo("default").isEmpty(), "the default site's superadmin is surfaced");
        assertTrue(encoder.matches("Site-Secret-9", siteAdmin.passwordHash()));

        // The seeded site is marked the catch-all default (platform.yml), so unbound hosts resolve to it.
        assertEquals("default", platform.get().defaultSite().orElseThrow(), "the seeded site is the default");

        // The provisioned store is valid for the now-pinned configuration.
        f.security().setAppMasterId(WizardService.APP_MASTER_ID);
        appUsers.validate(); // must not throw
    }

    @Test
    void theDefaultSiteAdministratorCanBeGivenARecoveryEmailAtSetup(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.wizard().completeSetup("root", "Sup3r-Secret-9", "Root", "r@x", "site-admin", "Site-Secret-9",
                " admin@site.test ");
        assertEquals("admin@site.test", f.masters().findByUsername("site-admin").orElseThrow().recoveryEmail());
    }

    @Test
    void completeSetupRefusesWhenASiteAlreadyExistsAndCommitsNothing(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        // An inconsistent WIZARD state: a site already exists (e.g. application.yml lost while sites/ survived).
        f.sites().create("stray", "Stray", List.of(), Locale.ENGLISH, List.of(Locale.ENGLISH));

        assertThrows(IllegalStateException.class, () -> f.wizard()
                .completeSetup("root", "Sup3r-Secret-9", "Root", "r@x", "site-admin", "Site-Secret-9"));

        // Nothing was committed: no application superadmin, no pin, no relaunch (fail loud, not half-done).
        assertTrue(f.appUsers().findByUsername("root").isEmpty(), "the application superadmin was not written");
        assertFalse(f.restarter().restarted, "the relaunch was not triggered");
        assertFalse(Files.exists(tmp.resolve("application.yml")), "the pin file was not written");
    }

    @Test
    void completeSetupMergesThePinPreservingExistingConfig(@TempDir Path tmp) throws IOException {
        // The installer (or a hand-edit) pre-configured TLS + a port BEFORE first-run setup.
        Files.writeString(tmp.resolve("application.yml"), """
                server:
                  port: 443
                runway:
                  tls:
                    enabled: true
                    keystore: /opt/runway-data/external/tls/runway.p12
                    keystore-password: "s3cr3t"
                """, StandardCharsets.UTF_8);

        fixture(tmp).wizard().completeSetup("root", "Sup3r-Secret-9", "Root", "r@x", "site-admin", "Site-Secret-9");

        String config = Files.readString(tmp.resolve("application.yml"), StandardCharsets.UTF_8);
        assertTrue(config.contains("app-master-id: " + WizardService.APP_MASTER_ID), config);
        assertTrue(config.contains("runway.p12") && config.contains("s3cr3t"), "TLS config survived the merge: " + config);
        assertTrue(config.contains("443"), "server.port survived the merge: " + config);
    }

    @Test
    void completeSetupRefusesToClobberUnparseableConfig(@TempDir Path tmp) throws IOException {
        String garbage = "runway: [this is: not valid yaml";
        Files.writeString(tmp.resolve("application.yml"), garbage, StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> fixture(tmp).wizard()
                .completeSetup("root", "Sup3r-Secret-9", "Root", "r@x", "site-admin", "Site-Secret-9"));

        assertEquals(garbage, Files.readString(tmp.resolve("application.yml"), StandardCharsets.UTF_8),
                "an unparseable config is left untouched, never clobbered");
    }
}
