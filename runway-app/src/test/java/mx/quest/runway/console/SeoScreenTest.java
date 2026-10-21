package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.seo.SeoConfigService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The tenant SEO screen: renders + pre-fills the form, saves (blank→null), and is CONFIGURE_SITE-gated. */
@SpringBootTest
class SeoScreenTest {

    private static final String PW = "Seo-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-3b");
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
                Set.of(Roles.ADMIN), List.of()));      // wildcard → has CONFIGURE_SITE
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // no CONFIGURE_SITE
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
    private SeoConfigService seo;
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
    void theFormRenders() throws Exception {
        mvc.perform(get("/__admin/seo").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/seo\"")))
                .andExpect(content().string(Matchers.containsString("name=\"ogImage\"")))
                .andExpect(content().string(Matchers.containsString("name=\"extraHead\"")));
    }

    @Test
    void saveThenTheConfigIsPersistedAndPreFilledOnReload() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/seo").session(admin).with(csrf())
                        .param("ogImage", "https://acme.test/og.png")
                        .param("twitterCard", "summary_large_image")
                        .param("googleVerification", "g-token")
                        .param("ogType", "")) // blank
                .andExpect(status().is3xxRedirection());

        assertEquals("https://acme.test/og.png", seo.get("acme").ogImage());
        assertEquals("summary_large_image", seo.get("acme").twitterCard());
        assertNull(seo.get("acme").ogType(), "a blank field is stored as null, not \"\"");

        // The saved value pre-fills the form on the next render (proves the .th.xml value binding works).
        mvc.perform(get("/__admin/seo").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("https://acme.test/og.png")))
                .andExpect(content().string(Matchers.containsString(
                        "id=\"seo-tw-large\" selected=\"selected\"")));
    }

    @Test
    void anUnknownCardOrContentTypeIsRefusedAndNothingIsSaved() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        String before = seo.get("acme").twitterCard();
        mvc.perform(post("/__admin/seo").session(admin).with(csrf()).param("twitterCard", "player"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attribute("error",
                        "The Twitter card must be summary or summary_large_image, or Automatic."));
        mvc.perform(post("/__admin/seo").session(admin).with(csrf()).param("ogType", "web site!"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("error"));
        assertEquals(before, seo.get("acme").twitterCard(), "a refused save changes nothing");
        mvc.perform(post("/__admin/seo").session(admin).with(csrf()).param("ogType", "Video.Movie")
                        .param("twitterCard", "SUMMARY"))
                .andExpect(status().is3xxRedirection());
        assertEquals("video.movie", seo.get("acme").ogType(), "stored lowercased");
        assertEquals("summary", seo.get("acme").twitterCard());
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSeeTheScreen() throws Exception {
        mvc.perform(get("/__admin/seo").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
