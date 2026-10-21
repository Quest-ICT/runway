package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.backup.BackupService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** App-realm backups: render, download any site, and create a NEW site from an uploaded backup. */
@SpringBootTest
class AppBackupsScreenTest {

    private static final String PW = "App-Bk-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-appbk");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), "https://acme.test", SiteStatus.ACTIVE));
        Files.createDirectories(layout.siteDir("acme").resolve("templates"));
        Files.writeString(layout.siteDir("acme").resolve("templates/home.html"), "ACME_HOME", StandardCharsets.UTF_8);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path appUsers = external.resolve("app-users.yml");
        users.save(appUsers, new User("app-master", "admin", hash, "Admin", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(appUsers, new User("app-op", "op", hash, "Op", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of())); // reaches the app chain, but not a platform master
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.storage.backups", () -> external.resolve("backups").toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteStore sites;
    @Autowired
    private BackupService backups;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin("/app/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void theScreenRendersCreateAndPerSite() throws Exception {
        mvc.perform(get("/__app/backups").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__app/backups/create-site\"")))
                .andExpect(content().string(Matchers.containsString("acme")))
                // The off-site folder is the server's (settings-homes.yml): shown, never edited.
                .andExpect(content().string(Matchers.containsString("Off-site copies: none.")));
    }

    @Test
    void downloadStreamsAZipOfAnySite() throws Exception {
        mvc.perform(get("/__app/backups/acme/download").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")));
    }

    @Test
    void createSiteFromAnUploadedBackupClonesUnderANewId() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        backups.backup("acme", baos);
        MockMultipartFile file = new MockMultipartFile("file", "acme.zip", "application/zip", baos.toByteArray());

        mvc.perform(multipart("/__app/backups/create-site").file(file).session(sessionFor("admin")).with(csrf())
                        .param("newId", "clone").param("name", "Clone").param("hosts", "clone.test"))
                .andExpect(status().is3xxRedirection());

        assertTrue(sites.exists("clone"), "the new site exists");
        assertEquals("clone", sites.find("clone").orElseThrow().id(), "site.yml rewritten to the new id");
        assertEquals("Clone", sites.find("clone").orElseThrow().name());
        assertTrue(sites.exists("acme"), "the source site is untouched");
    }

    /** An archive from a newer Runway: the operator is told to upgrade, and nothing is replaced or created. */
    @Test
    void aNewerFormatBackupIsRefusedWithTheUpgradeMessage() throws Exception {
        MockMultipartFile future = new MockMultipartFile("file", "future.zip", "application/zip", newerFormatArchive());
        MockHttpSession admin = sessionFor("admin");

        mvc.perform(multipart("/__app/backups/acme/restore").file(future).session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("newer version of Runway")))
                .andExpect(flash().attribute("error", Matchers.containsString("format 2")));
        assertEquals("ACME_HOME", Files.readString(acmeHome(), StandardCharsets.UTF_8), "the site was not replaced");

        mvc.perform(multipart("/__app/backups/create-site").file(future).session(admin).with(csrf())
                        .param("newId", "future").param("name", "Future"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("newer version of Runway")));
        assertFalse(sites.exists("future"), "no site was created");
    }

    @Test
    void aBadOrClaimedHostIsReportedAsAHostProblem() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        backups.backup("acme", baos);
        MockMultipartFile file = new MockMultipartFile("file", "acme.zip", "application/zip", baos.toByteArray());
        var admin = sessionFor("admin");
        mvc.perform(multipart("/__app/backups/create-site").file(file).session(admin).with(csrf())
                        .param("newId", "copy1").param("hosts", "acme.test"))
                .andExpect(flash().attribute("error", Matchers.startsWith("Those hosts cannot be used:")));
        mvc.perform(multipart("/__app/backups/create-site").file(file).session(admin).with(csrf())
                        .param("newId", "copy2").param("hosts", "not a host!"))
                .andExpect(flash().attribute("error", Matchers.containsString("is not a valid hostname")));
        assertFalse(sites.exists("copy1"));
        assertFalse(sites.exists("copy2"));
    }

    @Autowired
    private StorageLayout layout;

    private Path acmeHome() {
        return layout.siteDir("acme").resolve("templates/home.html");
    }

    /** A minimal archive whose manifest declares format 2 — what a later Runway might write. */
    static byte[] newerFormatArchive() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.yml"));
            zip.write("siteId: acme\nformatVersion: 2\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("site/site.yml"));
            zip.write("id: acme\nname: Acme\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("site/templates/home.html"));
            zip.write("FROM_THE_FUTURE".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void aNonMasterAppOperatorIsDeniedTheseCrossTenantActions() throws Exception {
        mvc.perform(get("/__app/backups").session(sessionFor("op")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/__app/backups/acme/download").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
