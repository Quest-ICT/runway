package mx.quest.runway.console;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The Static files screen end to end: browse, upload several files (one refused, the rest kept), create a folder,
 * delete, the doc/ folder left to Documents, the public URL serving what was uploaded, and the CONFIGURE_SITE gate.
 */
@SpringBootTest
class StaticFilesScreenTest {

    private static final String PW = "Static-Files-7";
    private static Path staticDir;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-static");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        new GrantStore(layout, yaml).saveSiteGrants("acme", GrantFile.empty());
        staticDir = layout.siteDir("acme").resolve("static");
        Files.createDirectories(staticDir.resolve("doc"));
        Files.writeString(staticDir.resolve("doc/brochure.pdf"), "pdf", StandardCharsets.UTF_8);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void uploadBrowseServeAndDelete() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/static/folder").session(admin).with(csrf()).param("name", "css"))
                .andExpect(redirectedUrl("/__admin/static"));
        mvc.perform(multipart("/__admin/static/upload").file(new MockMultipartFile("files", "site.css", "text/css",
                                "body{color:red}".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "evil.html", "text/html", "<p>".getBytes()))
                        .param("dir", "css").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/__admin/static?dir=css"))
                .andExpect(flash().attribute("flash", "Uploaded 1 file: site.css."))
                .andExpect(flash().attribute("error",
                        "Not uploaded: evil.html: files of this type cannot be uploaded here"));
        assertTrue(Files.exists(staticDir.resolve("css/site.css")));
        assertFalse(Files.exists(staticDir.resolve("css/evil.html")));

        String root = mvc.perform(get("/__admin/static").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(root.contains("/__admin/static?dir=css"), "a folder links into itself");
        assertTrue(root.contains("/__admin/documents"), "doc/ points to Documents");
        String css = mvc.perform(get("/__admin/static").param("dir", "css").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(css.contains("href=\"/static/css/site.css\""), "a file links to its public address");

        mvc.perform(get("/static/css/site.css")).andExpect(status().isOk())
                .andExpect(content().string("body{color:red}"));

        mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", "css"))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", "css/site.css"))
                .andExpect(redirectedUrl("/__admin/static?dir=css"));
        mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", "css"))
                .andExpect(redirectedUrl("/__admin/static"));
        assertFalse(Files.exists(staticDir.resolve("css")));
    }

    @Test
    void longAndHandPlacedNamesCanBeOpenedAndDeleted() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        String longName = "vendors-main.4f3a9c1e7b2d8a6f0e5c3b1a9d7f2e4c6b8a0d1f3e5c7b9a.chunk.min.js";
        mvc.perform(multipart("/__admin/static/upload").file(new MockMultipartFile("files", longName,
                        "text/javascript", "x".getBytes())).session(admin).with(csrf()))
                .andExpect(flash().attributeExists("flash"));
        assertTrue(Files.exists(staticDir.resolve(longName)));
        mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", longName))
                .andExpect(flash().attribute("flash", "Deleted."));
        assertFalse(Files.exists(staticDir.resolve(longName)));

        // Put there on the server, or by a restore: names the upload would have made safe.
        String folder = "g".repeat(70);
        Files.createDirectories(staticDir.resolve(folder));
        Files.writeString(staticDir.resolve(folder + "/señal.svg"), "<svg/>");
        Files.writeString(staticDir.resolve("my file.css"), "a{}");
        mvc.perform(get("/__admin/static").param("dir", folder).session(admin)).andExpect(status().isOk());
        for (String path : new String[] {folder + "/señal.svg", "my file.css", folder}) {
            mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", path))
                    .andExpect(flash().attribute("flash", "Deleted."));
        }
        assertFalse(Files.exists(staticDir.resolve(folder)));
        assertFalse(Files.exists(staticDir.resolve("my file.css")));

        for (String bad : new String[] {"../site.yml", "css/../../site.yml", "..", "a\\b.css"}) {
            mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", bad))
                    .andExpect(flash().attribute("error", "That is not a path inside the static folder."));
        }
        assertTrue(Files.exists(staticDir.getParent().resolve("site.yml")), "the site's own file is untouched");
    }

    /**
     * A hand-placed {@code " lead.css"} was listed with a Delete button, but the form's path was
     * trimmed to {@code "lead.css"}, so nothing was deleted; and a dot-name such as {@code .htaccess}, which is
     * served at {@code /static/} like any file, was listed and could not be deleted. Both delete now.
     */
    @Test
    void namesWithSpacesAroundThemAndDotNamesDelete() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        Files.writeString(staticDir.resolve(" lead.css"), "a{}");
        Files.writeString(staticDir.resolve(".htaccess"), "x");
        String root = mvc.perform(get("/__admin/static").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(root.contains(".htaccess") && root.contains(" lead.css"), "both are listed");
        for (String name : new String[] {" lead.css", ".htaccess"}) {
            mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", name))
                    .andExpect(flash().attribute("flash", "Deleted."));
            assertFalse(Files.exists(staticDir.resolve(name)), name);
        }
        mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", "  "))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));
        assertTrue(Files.isDirectory(staticDir), "a blank path is never the static folder");
    }

    @Test
    void documentsAndOutsidePathsAreRefused() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/static/delete").session(admin).with(csrf()).param("path", "doc/brochure.pdf"))
                .andExpect(flash().attribute("error", "The doc folder is managed on the Documents screen."));
        assertTrue(Files.exists(staticDir.resolve("doc/brochure.pdf")));
        mvc.perform(get("/__admin/static").param("dir", "../templates").session(admin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/__admin/static").param("dir", "nope").session(admin))
                .andExpect(status().isNotFound());
        String doc = mvc.perform(get("/__admin/static").param("dir", "doc").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(doc.contains("id=\"sf-upload-form\""), "no upload into doc/");
    }

    @Test
    void withoutConfigureSiteTheScreenIsForbidden() throws Exception {
        MockHttpSession op = sessionFor("op");
        mvc.perform(get("/__admin/static").session(op)).andExpect(status().isForbidden());
        mvc.perform(multipart("/__admin/static/upload").file(new MockMultipartFile("files", "a.css", "text/css",
                        "x".getBytes())).session(op).with(csrf()))
                .andExpect(status().isForbidden());
        assertFalse(Files.exists(staticDir.resolve("a.css")));
    }
}
