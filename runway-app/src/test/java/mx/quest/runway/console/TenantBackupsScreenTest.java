package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import mx.quest.runway.backup.BackupService;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The tenant Backups screen: render, create/list, download, master-only restore round-trip, gating. */
@SpringBootTest
class TenantBackupsScreenTest {

    private static final String PW = "Backups-Screen-7";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-b2");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));       // wildcard → may manage + restore
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));      // no MANAGE_BACKUPS
        users.save(siteUsers, new User("acme-keeper", "keeper", hash, "Keeper", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));      // delegated MANAGE_BACKUPS, not an administrator
        new GrantStore(layout, yaml).saveSiteGrants("acme", GrantFile.empty()
                .withUserPermission("acme-keeper", "MANAGE_BACKUPS"));
        // The site superadmin: a tenant-master assigned to acme (not a wildcard ADMIN — the other admin path).
        users.save(external.resolve("site-users.yml"), new User("owner-id", "owner", hash, "Owner", "", true, true,
                Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of("acme")));
        // A second site, for isolation: its own administrator, on its own host.
        new SiteStore(layout, yaml).save(new Site("beta", "Beta", List.of("beta.test"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        users.save(layout.siteDir("beta").resolve("users.yml"), new User("beta-admin", "betaadmin", hash, "Beta",
                "", true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // A page on disk so a backup has something to restore.
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("home.html"), "ORIGINAL_CONTENT", StandardCharsets.UTF_8);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.storage.backups", () -> external.resolve("backups").toString()); // not the module dir
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private BackupService backups;
    @MockitoSpyBean
    private SiteLogService siteLog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private static RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    private MockHttpSession sessionOn(String host, String username) throws Exception {
        MvcResult r = mvc.perform(post("/login").with(host(host)).with(csrf())
                .param("username", username).param("password", PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    /** A stored backup of acme, made as its administrator; returns its name. */
    private String storedBackup() throws Exception {
        mvc.perform(post("/__admin/backups/create").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        return backups.list("acme").get(0).name();
    }

    // --- a delegated MANAGE_BACKUPS holder gets transfer copies only -----------------------------

    /** Every route, as the delegated holder: the full downloads are 403 server-side, everything else works. */
    @Test
    void aDelegatedHolderCreatesBackupsAndDownloadsTransferCopiesButNoFullBackup() throws Exception {
        MockHttpSession keeper = sessionFor("keeper");
        int before = backups.list("acme").size();
        mvc.perform(post("/__admin/backups/create").session(keeper).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertEquals(before + 1, backups.list("acme").size(), "the holder may still save a backup on the server");
        String name = backups.list("acme").get(0).name();

        mvc.perform(get("/__admin/backups/download-transfer").session(keeper))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("acme-transfer-")));
        mvc.perform(get("/__admin/backups/download").session(keeper))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Content-Disposition"));
        mvc.perform(get("/__admin/backups/" + name + "/download").session(keeper))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Content-Disposition"));
        // 403 before the name is looked up: the answer does not reveal whether a backup exists.
        mvc.perform(get("/__admin/backups/no-such-backup.zip/download").session(keeper))
                .andExpect(status().isForbidden());
        // Restore stays an administrator's, as before.
        mvc.perform(post("/__admin/backups/" + name + "/restore").session(keeper).with(csrf()))
                .andExpect(status().isForbidden());
        // Deleting a stored backup is unchanged (MANAGE_BACKUPS).
        mvc.perform(post("/__admin/backups/" + name + "/delete").session(keeper).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertEquals(before, backups.list("acme").size());
    }

    /** Both administrator paths — the wildcard ADMIN tier and the site superadmin (tenant master) — get every download. */
    @Test
    void theSiteAdministratorsDownloadFullBackupsFreshAndStored() throws Exception {
        String name = storedBackup();
        for (String admin : List.of("admin", "owner")) {
            MockHttpSession session = sessionFor(admin);
            mvc.perform(get("/__admin/backups/download").session(session))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", Matchers.containsString("acme-backup-")));
            mvc.perform(get("/__admin/backups/" + name + "/download").session(session))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", Matchers.containsString(name)));
            mvc.perform(get("/__admin/backups/download-transfer").session(session))
                    .andExpect(status().isOk());
        }
    }

    // --- the site's audit log ----------------------------------------------------------------------

    /** Each act that took effect is one line in the site's own audit log, with who did it and what. */
    @Test
    void everyBackupActIsRecordedInTheSitesAuditLog() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        clearInvocations(siteLog);
        mvc.perform(post("/__admin/backups/create").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        String name = backups.list("acme").get(0).name();
        verify(siteLog).audit("acme", "admin", "backup.saved", name);

        mvc.perform(get("/__admin/backups/download").session(admin)).andExpect(status().isOk());
        verify(siteLog).audit("acme", "admin", "backup.downloaded", "full");
        mvc.perform(get("/__admin/backups/download-transfer").session(admin)).andExpect(status().isOk());
        verify(siteLog).audit("acme", "admin", "backup.downloaded", "transfer");
        mvc.perform(get("/__admin/backups/" + name + "/download").session(admin)).andExpect(status().isOk());
        verify(siteLog).audit("acme", "admin", "backup.downloaded", "full " + name);

        mvc.perform(post("/__admin/backups/" + name + "/restore").session(admin).with(csrf()))
                .andExpect(flash().attributeExists("flash"));
        verify(siteLog).audit("acme", "admin", "site.restored", name);
        byte[] zip = mvc.perform(get("/__admin/backups/download").session(admin)).andReturn()
                .getResponse().getContentAsByteArray();
        mvc.perform(multipart("/__admin/backups/restore")
                        .file(new MockMultipartFile("file", "acme.zip", "application/zip", zip))
                        .session(admin).with(csrf()))
                .andExpect(flash().attributeExists("flash"));
        verify(siteLog).audit("acme", "admin", "site.restored", "(upload)");

        mvc.perform(post("/__admin/backups/" + name + "/delete").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(siteLog).audit("acme", "admin", "backup.deleted", name);
    }

    /** A refused, failed or empty act writes nothing: the log says what happened, not what was tried. */
    @Test
    void aRefusedOrFailedBackupActIsNotRecorded() throws Exception {
        String name = storedBackup();
        MockHttpSession keeper = sessionFor("keeper");
        MockHttpSession admin = sessionFor("admin");
        clearInvocations(siteLog);
        mvc.perform(get("/__admin/backups/download").session(keeper)).andExpect(status().isForbidden());
        mvc.perform(get("/__admin/backups/" + name + "/download").session(keeper))
                .andExpect(status().isForbidden());
        // A HEAD request (a link checker, a preview) takes no copy: answered, not recorded.
        for (String path : List.of("/__admin/backups/download", "/__admin/backups/download-transfer",
                "/__admin/backups/" + name + "/download")) {
            mvc.perform(head(path).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")));
        }
        // A range that resumes a download is not a new one.
        mvc.perform(get("/__admin/backups/" + name + "/download").session(admin).header("Range", "bytes=10-"))
                .andExpect(status().isPartialContent());
        mvc.perform(post("/__admin/backups/" + name + "/restore").session(keeper).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__admin/backups/no-such-backup.zip/delete").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__admin/backups/no-such-backup.zip/restore").session(admin).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(multipart("/__admin/backups/restore")
                        .file(new MockMultipartFile("file", "junk.zip", "application/zip", new byte[] {1, 2, 3}))
                        .session(admin).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        verify(siteLog, never()).audit(anyString(), anyString(), startsWith("backup."), anyString());
        verify(siteLog, never()).audit(anyString(), anyString(), eq("site.restored"), anyString());
    }

    /** Without MANAGE_BACKUPS at all, nothing — not even a transfer copy. */
    @Test
    void anOperatorWithoutTheGrantGetsNoDownloadOfAnyKind() throws Exception {
        String name = storedBackup();
        MockHttpSession op = sessionFor("op");
        mvc.perform(get("/__admin/backups/download-transfer").session(op)).andExpect(status().isForbidden());
        mvc.perform(get("/__admin/backups/download").session(op)).andExpect(status().isForbidden());
        mvc.perform(get("/__admin/backups/" + name + "/download").session(op)).andExpect(status().isForbidden());
    }

    /** The screen: the holder sees no full-download control (and is told why); an administrator sees them all. */
    @Test
    void theScreenHidesTheFullDownloadsFromADelegatedHolder() throws Exception {
        String name = storedBackup();
        String keeperPage = mvc.perform(get("/__admin/backups").session(sessionFor("keeper")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(keeperPage.contains("/__admin/backups/download-transfer"), "the transfer copy is offered");
        assertTrue(keeperPage.contains("/__admin/backups/create"), "saving on the server is offered");
        assertTrue(keeperPage.contains(name), "stored backups are listed");
        assertTrue(!keeperPage.contains("href=\"/__admin/backups/download\""), "no fresh full download");
        assertTrue(!keeperPage.contains("/" + name + "/download"), "no stored full download");
        assertTrue(!keeperPage.contains("/" + name + "/restore"), "no restore");
        assertTrue(keeperPage.contains("id=\"bk-full-admin-only\""), "the holder is told why");

        String adminPage = mvc.perform(get("/__admin/backups").session(sessionFor("owner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(adminPage.contains("href=\"/__admin/backups/download\""));
        assertTrue(adminPage.contains("/" + name + "/download"));
        assertTrue(!adminPage.contains("id=\"bk-full-admin-only\""));
    }

    /** Tenancy: acme's session reaches nothing on beta's host, and beta's administrator cannot fetch acme's backup. */
    @Test
    void backupDownloadsStayInsideTheirOwnSite() throws Exception {
        String acmeBackup = storedBackup();
        MockHttpSession acmeAdmin = sessionFor("admin");
        int status = mvc.perform(get("/__admin/backups/download").session(acmeAdmin).with(host("beta.test")))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 403 || (status >= 300 && status < 400), "acme's session on beta's host: " + status);

        MockHttpSession betaAdmin = sessionOn("beta.test", "betaadmin");
        mvc.perform(get("/__admin/backups/" + acmeBackup + "/download").session(betaAdmin).with(host("beta.test")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/__admin/backups/download").session(betaAdmin).with(host("beta.test")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString("beta-backup-")));
    }

    @Test
    void theScreenRendersWithCreateAndStoredSections() throws Exception {
        mvc.perform(get("/__admin/backups").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/__admin/backups/create")))
                .andExpect(content().string(Matchers.containsString("data-listing-table=\"runway.admin.backups\"")))
                // The browser-tab title ends in the SITE ID (not the platform name) so simultaneously-open site
                // consoles are distinguishable from their tabs alone — verified through the real shell render,
                // not just the model unit test.
                .andExpect(content().string(Matchers.containsString("· acme</title>")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("· Runway</title>"))))
                // The operator's policy, read-only, at its defaults: no schedule, the newest 30 kept.
                .andExpect(content().string(Matchers.containsString("Automatic backups: off")))
                .andExpect(content().string(Matchers.containsString("Kept: the newest 30.")))
                .andExpect(content().string(Matchers.containsString("Set by the platform operator.")));
    }

    @Test
    void createThenTheBackupIsListed() throws Exception {
        mvc.perform(post("/__admin/backups/create").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(backups.list("acme").size() >= 1, "a stored backup exists after create");
    }

    @Test
    void downloadStreamsAZipAttachment() throws Exception {
        mvc.perform(get("/__admin/backups/download").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")));
    }

    @Test
    void restoreFromAStoredBackupRoundTripsThroughTheController() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/backups/create").session(admin).with(csrf())).andExpect(status().is3xxRedirection());
        String name = backups.list("acme").get(0).name();

        // Mutate the site after the backup, then restore.
        Path home = external.resolve("sites").resolve("acme").resolve("templates").resolve("home.html");
        Files.writeString(home, "MUTATED", StandardCharsets.UTF_8);

        mvc.perform(post("/__admin/backups/" + name + "/restore").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertEquals("ORIGINAL_CONTENT", Files.readString(home, StandardCharsets.UTF_8), "the page was restored");
    }

    /** After a restore the screen shows how the site is reached, read-only: a restore here keeps the hosts. */
    @Test
    void aRestoreIsFollowedByTheSitesHostsForReview() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/backups/create").session(admin).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/__admin/backups").session(admin)).andExpect(status().isOk()); // the create's redirect
        String name = backups.list("acme").get(0).name();
        mvc.perform(post("/__admin/backups/" + name + "/restore").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(AppBackupsController.REVIEW_HOSTS, Boolean.TRUE));

        MvcResult shown = mvc.perform(get("/__admin/backups").session(admin)).andExpect(status().isOk()).andReturn();
        String page = shown.getResponse().getContentAsString();
        assertTrue(page.contains("id=\"bk-review\""), "the review card is shown");
        assertTrue(page.contains("How the site is reached"));
        assertTrue(page.contains("only a platform administrator can change them"));
        assertTrue(page.contains(">localhost<"), "the site's host");
        assertTrue(page.contains("not set, so https://localhost (https:// and the first host)"), "the derived base URL");
        assertTrue(!page.contains("/__app/sites/acme/hosts"), "no editor on the site console");

        String plain = mvc.perform(get("/__admin/backups").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(!plain.contains("id=\"bk-review\""), "no review card without a restore");
    }

    /** An archive from a newer Runway is refused on both restore paths, with the upgrade message. */
    @Test
    void aNewerFormatBackupIsRefusedWithTheUpgradeMessage() throws Exception {
        byte[] future = AppBackupsScreenTest.newerFormatArchive();
        MockHttpSession admin = sessionFor("admin");
        Path home = external.resolve("sites").resolve("acme").resolve("templates").resolve("home.html");
        String before = Files.readString(home, StandardCharsets.UTF_8);

        mvc.perform(multipart("/__admin/backups/restore")
                        .file(new MockMultipartFile("file", "future.zip", "application/zip", future))
                        .session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("newer version of Runway")))
                .andExpect(flash().attribute("error", Matchers.containsString("platform operator")));
        assertEquals(before, Files.readString(home, StandardCharsets.UTF_8), "the site was not replaced");

        mvc.perform(post("/__admin/backups/create").session(admin).with(csrf())).andExpect(status().is3xxRedirection());
        String name = backups.list("acme").get(0).name();
        Files.write(external.resolve("backups").resolve("acme").resolve(name), future);
        mvc.perform(post("/__admin/backups/" + name + "/restore").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("newer version of Runway")));
        assertEquals(before, Files.readString(home, StandardCharsets.UTF_8), "the site was not replaced");
    }

    @Test
    void anOperatorWithoutManageBackupsCannotSeeTheScreen() throws Exception {
        mvc.perform(get("/__admin/backups").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
