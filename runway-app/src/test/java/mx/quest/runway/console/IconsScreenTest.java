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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.icon.SiteIcon;
import mx.quest.runway.icon.SiteIconService;
import mx.quest.runway.icon.SiteManifestService;
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

/** The tenant Icons screen: render, per-slot upload/delete, manifest save, and CONFIGURE_SITE gating. */
@SpringBootTest
class IconsScreenTest {

    private static final String PW = "Icons-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-4b");
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
                Set.of(Roles.ADMIN), List.of()));
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
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
    private SiteIconService icons;
    @Autowired
    private SiteManifestService manifests;
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
    void theIconsScreenRendersSlotsAndNoLongerCarriesTheManifestForm() throws Exception {
        mvc.perform(get("/__admin/icons").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/favicon.svg")))
                // the Web app manifest form moved to its own /__admin/manifest screen
                .andExpect(content().string(Matchers.not(Matchers.containsString("action=\"/__admin/manifest\""))));
    }

    @Test
    void theManifestScreenRendersItsOwnForm() throws Exception {
        mvc.perform(get("/__admin/manifest").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/manifest\"")));
    }

    @Test
    void uploadThenTheSlotIsPresentAndDeleteRemovesIt() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        MockMultipartFile file = new MockMultipartFile("file", "favicon.svg", "image/svg+xml", "<svg/>".getBytes());
        mvc.perform(multipart("/__admin/icons/svg-favicon/upload").file(file).session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(icons.present("acme", SiteIcon.SVG_FAVICON), "the icon is stored after upload");

        mvc.perform(post("/__admin/icons/svg-favicon/delete").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertFalse(icons.present("acme", SiteIcon.SVG_FAVICON), "the icon is gone after delete");
    }

    @Test
    void manifestSavePersistsTheFields() throws Exception {
        mvc.perform(post("/__admin/manifest").session(sessionFor("admin")).with(csrf())
                        .param("name", "Acme").param("display", "standalone").param("themeColor", "#0d6efd"))
                .andExpect(status().is3xxRedirection());
        assertEquals("Acme", manifests.get("acme").name());
        assertEquals("standalone", manifests.get("acme").display());
    }

    @Test
    void anInvalidColourDisplayOrLanguageIsRefusedAndNothingIsSaved() throws Exception {
        var admin = sessionFor("admin");
        mvc.perform(post("/__admin/manifest").session(admin).with(csrf())
                        .param("name", "Acme").param("display", "standalone").param("themeColor", "#0d6efd"))
                .andExpect(status().is3xxRedirection());
        for (String[] bad : new String[][] {{"themeColor", "blue"}, {"backgroundColor", "#12345"},
                {"display", "window"}, {"lang", "english"}}) {
            mvc.perform(post("/__admin/manifest").session(admin).with(csrf()).param("name", "Changed")
                            .param(bad[0], bad[1]))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                            .attributeExists("error"));
        }
        assertEquals("Acme", manifests.get("acme").name(), "a refused save changes nothing");
        mvc.perform(post("/__admin/manifest").session(admin).with(csrf()).param("name", "Acme")
                        .param("themeColor", "#ABC").param("display", "MINIMAL-UI").param("lang", "pt-br"))
                .andExpect(status().is3xxRedirection());
        assertEquals("#abc", manifests.get("acme").themeColor());
        assertEquals("minimal-ui", manifests.get("acme").display());
        assertEquals("pt-BR", manifests.get("acme").lang());
        mvc.perform(get("/__admin/manifest").session(admin))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<option value=\"minimal-ui\" id=\"wm-d-minimal\" selected=\"selected\"")));
    }

    @Test
    void generateFromAnSvgFillsAllTheRasterSlots() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + "<rect width=\"10\" height=\"10\" fill=\"#123\"/></svg>";
        MockMultipartFile file = new MockMultipartFile("svg", "logo.svg", "image/svg+xml",
                svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/__admin/icons/generate").file(file).session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(icons.present("acme", SiteIcon.SVG_FAVICON));
        assertTrue(icons.present("acme", SiteIcon.APPLE_TOUCH));
        assertTrue(icons.present("acme", SiteIcon.ICON_192));
        assertTrue(icons.present("acme", SiteIcon.ICON_512));
        assertTrue(icons.present("acme", SiteIcon.FAVICON_ICO));
    }

    @Test
    void generateWithANonSvgReportsAnErrorFlash() throws Exception {
        // Isolation-independent (the class shares one temp dir across methods): assert the error is reported
        // rather than global emptiness. IconGenerationServiceTest proves a bad SVG writes nothing to disk.
        MockMultipartFile file = new MockMultipartFile("svg", "notreally.svg", "image/svg+xml",
                "this is not an svg".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/__admin/icons/generate").file(file).session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSeeEitherScreen() throws Exception {
        mvc.perform(get("/__admin/icons").session(sessionFor("op")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/__admin/manifest").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
