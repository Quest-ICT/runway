package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStorageCeilings;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
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

/**
 * A site's storage ceiling end to end: the operator sets it in the
 * site's Edit dialog on Sites (stored in {@code platform.yml}, applied at once, shown on the row and prefilled), the
 * site sees its usage against it read-only on Static files, and an upload over it is refused with a clear message on
 * both Static files and Documents while every stored file stays and can still be deleted.
 */
@SpringBootTest
class SiteStorageCeilingScreensTest {

    private static final String PW = "Storage-Ceiling-8";
    private static Path staticDir;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-ceiling");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme Corp", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        for (String id : new String[] {"bravo", "charlie"}) {
            sites.save(new Site(id, "Site " + id, List.of(id + ".test"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                    null, SiteStatus.ACTIVE));
        }
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("acme-admin", "admin", hash, "Admin", "",
                true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        new GrantStore(layout, yaml).saveSiteGrants("acme", GrantFile.empty());
        staticDir = layout.siteDir("acme").resolve("static");
        Files.createDirectories(staticDir);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add(SiteStorageCeilings.DEFAULT_KEY, () -> "1073741824"); // a 1 GiB platform default
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private PlatformSettingsStore platform;
    @Autowired
    private SiteStorageCeilings ceilings;
    @Autowired
    private SiteStore siteStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession tenantAdmin() throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user("admin").password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession appMaster() throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user("root").password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String staticScreen(MockHttpSession session) throws Exception {
        return mvc.perform(get("/__admin/static").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void editStorage(MockHttpSession session, String id, String storage) throws Exception {
        mvc.perform(post("/__app/sites/" + id + "/edit").session(session).with(csrf())
                        .param("name", "Site " + id).param("defaultLocale", "en").param("locales", "en")
                        .param("quota", "").param("storage", storage))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flash"));
    }

    @Test
    void theSiteSeesItsUsageAndAnUploadOverTheCeilingIsRefusedWhileFilesStay() throws Exception {
        platform.update(current -> current.withSiteStorageCeiling("acme", 100L));
        MockHttpSession admin = tenantAdmin();
        assertTrue(staticScreen(admin).contains("Static files and documents use 0 B of the 100 B this site may store."),
                "usage against the ceiling, read-only");

        mvc.perform(multipart("/__admin/static/upload").file(new MockMultipartFile("files", "a.js", "text/javascript",
                        new byte[60])).session(admin).with(csrf()))
                .andExpect(flash().attribute("flash", "Uploaded 1 file: a.js."));
        mvc.perform(multipart("/__admin/static/upload").file(new MockMultipartFile("files", "b.js", "text/javascript",
                        new byte[60])).session(admin).with(csrf()))
                .andExpect(flash().attribute("error", "Not uploaded: b.js: it would take the site's files over their "
                        + "storage limit; delete files you no longer need, or ask the platform operator to raise the limit"));
        assertFalse(Files.exists(staticDir.resolve("b.js")));
        mvc.perform(multipart("/__admin/documents/upload").file(new MockMultipartFile("file", "r.pdf",
                        "application/pdf", new byte[60])).session(admin).with(csrf()))
                .andExpect(flash().attribute("error", "Not uploaded: the site's files would go over their storage "
                        + "limit. Delete files you no longer need, or ask the platform operator to raise the limit."));

        platform.update(current -> current.withSiteStorageCeiling("acme", 50L)); // lowered below the usage
        String over = staticScreen(admin);
        assertTrue(over.contains("Static files and documents use 60 B of the 50 B this site may store."));
        assertTrue(over.contains("are over their storage limit. Every file is kept and still served"), over);
        assertTrue(Files.exists(staticDir.resolve("a.js")), "a lower ceiling never deletes a file");
        mvc.perform(get("/static/a.js")).andExpect(status().isOk());

        mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", "a.js"))
                .andExpect(flash().attribute("flash", "Deleted."));
        String after = staticScreen(admin);
        assertTrue(after.contains("use 0 B of the 50 B"), "the figure follows the delete at once");
        assertFalse(after.contains("are over their storage limit"));

        platform.update(current -> current.withSiteStorageCeiling("acme", 0L));
        assertTrue(staticScreen(admin).contains("Static files and documents use 0 B. This site has no storage limit."));
    }

    @Test
    void theOperatorSetsTheCeilingOnSitesAndItIsStoredShownAndPrefilled() throws Exception {
        MockHttpSession root = appMaster();
        editStorage(root, "bravo", "5 GB");
        assertEquals(5L << 30, platform.get().siteStorageCeilings().get("bravo"), "stored in bytes in platform.yml");
        assertEquals(SiteStorageCeilings.Source.SITE, ceilings.of("bravo").source());
        String html = mvc.perform(get("/__app/sites").session(root)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Storage limit 5.0 GB (this site)"), "the row shows the ceiling and its source");
        assertTrue(html.contains("Storage limit 1.0 GB (platform default)"), "a site without one shows the default");
        assertTrue(html.contains("name=\"storage\"") && html.contains("value=\"5GB\""),
                "the field shows the ceiling so an unchanged save keeps it");
        assertTrue(html.contains("Leave it empty to use the platform default (1.0 GB)."));

        editStorage(root, "bravo", "");
        assertFalse(platform.get().siteStorageCeilings().containsKey("bravo"), "blank returns to the default");
        assertEquals(1L << 30, ceilings.of("bravo").bytes());
    }

    @Test
    void anInvalidCeilingIsRefusedAndNothingChanges() throws Exception {
        mvc.perform(post("/__app/sites/charlie/edit").session(appMaster()).with(csrf())
                        .param("name", "Renamed").param("defaultLocale", "en").param("locales", "en")
                        .param("storage", "lots"))
                .andExpect(flash().attribute("error",
                        "The storage limit must be a size such as 5GB or 500MB, or 0 for no limit."));
        assertFalse(platform.get().siteStorageCeilings().containsKey("charlie"));
        assertEquals("Site charlie", siteStore.find("charlie").orElseThrow().name(), "not even the name is saved");
    }
}
