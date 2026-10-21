package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.document.DocumentService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The tenant Documents screen: render, upload, disable→public 404, delete, and MANAGE_DOCUMENTS gating. */
@SpringBootTest
class DocumentsScreenTest {

    private static final String PW = "Docs-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-5");
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
                Set.of(Roles.ADMIN), List.of()));      // has MANAGE_DOCUMENTS
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // no MANAGE_DOCUMENTS
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private DocumentService documents;
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

    @Test
    void theScreenRendersUploadAndList() throws Exception {
        mvc.perform(get("/__admin/documents").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/documents/upload\"")))
                .andExpect(content().string(Matchers.containsString("data-listing-table=\"runway.admin.documents\"")));
    }

    @Test
    void uploadThenTheDocumentServesAsAnAttachmentAndDisableWithholdsIt() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        MockMultipartFile file = new MockMultipartFile("file", "report.html", "text/html",
                "<script>alert(1)</script>".getBytes());
        mvc.perform(multipart("/__admin/documents/upload").file(file).session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(documents.list("acme").stream().anyMatch(d -> d.path().equals("report.html")));

        // Publicly served as a nosniff attachment (never inline text/html on the site origin).
        mvc.perform(get("/static/doc/report.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.containsString("application/octet-stream")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")));

        // Disable → the same public URL now 404s, but the file is not deleted.
        mvc.perform(post("/__admin/documents/toggle").session(admin).with(csrf())
                        .param("path", "report.html").param("disable", "true"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/static/doc/report.html")).andExpect(status().isNotFound());
        assertTrue(documents.list("acme").stream().filter(d -> d.path().equals("report.html")).findFirst()
                .orElseThrow().disabled(), "still present, just withheld");
    }

    @Test
    void deleteRemovesTheDocument() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        documents.add("acme", "gone.txt", "bye".getBytes());
        mvc.perform(post("/__admin/documents/delete").session(admin).with(csrf()).param("path", "gone.txt"))
                .andExpect(status().is3xxRedirection());
        assertFalse(documents.list("acme").stream().anyMatch(d -> d.path().equals("gone.txt")));
    }

    @Test
    void aSpacedFilenameIsSanitizedToAUrlSafeNameThatServes() throws Exception {
        documents.add("acme", "my report.pdf", "PDF".getBytes()); // stored as my_report.pdf
        assertTrue(documents.list("acme").stream().anyMatch(d -> d.path().equals("my_report.pdf")));
        mvc.perform(get("/static/doc/my_report.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")));
    }

    @Test
    void anOperatorWithoutManageDocumentsCannotSeeTheScreen() throws Exception {
        mvc.perform(get("/__admin/documents").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
