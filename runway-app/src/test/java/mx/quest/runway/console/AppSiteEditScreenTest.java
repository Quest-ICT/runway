package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import mx.quest.runway.log.AppActivityLog;
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

/**
 * The app-realm <b>site edit</b> (name + languages) end to end: the master edits through the Sites screen with the
 * SAME validation as the tenant's Site config (blank name, missing default, default outside the available set),
 * the change is persisted through the lifecycle service and journaled, and a plain operator is refused with 403.
 * Each test edits its own site, so they are order-independent.
 */
@SpringBootTest
class AppSiteEditScreenTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final String PW = "Site-Edit-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-siteedit");
        var enc = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
        UserStore users = new UserStore(new YamlStore());
        Path appUsers = external.resolve("app-users.yml");
        users.save(appUsers, new User("app-master", "admin", enc.encode(PW), "Admin", "a@x",
                true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(appUsers, new User("app-op", "operator", enc.encode(PW), "Operator", "o@x",
                true, false, Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        SiteStore sites = new SiteStore(StorageLayout.from(props), new YamlStore());
        for (String id : new String[] {"alpha", "bravo", "charlie", "delta", "echo"}) {
            sites.save(new Site(id, "Site " + id, List.of(id + ".test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        }
        // The operator may READ the sites list (VIEW_TENANCIES) but holds no platform authority.
        new GrantStore(StorageLayout.from(props), new YamlStore())
                .saveAppGrants(GrantFile.empty().withUserPermission("app-op", "VIEW_TENANCIES"));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteStore siteStore;
    @Autowired
    private AppActivityLog activity;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession login(String user) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(user).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Site site(String id) {
        return siteStore.find(id).orElseThrow();
    }

    @Test
    void theMasterSeesThePerRowEditFormPrefilled() throws Exception {
        String html = mvc.perform(get("/__app/sites").session(login("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__app/sites/alpha/edit\""), "the edit form targets the edit endpoint");
        assertTrue(html.contains("si-edit-modal-0"), "the per-row modal id rendered from the row index");
        assertTrue(html.contains("value=\"Site alpha\""), "the name field is prefilled");
        assertTrue(html.contains("name=\"locales\""), "the languages field is present");
    }

    @Test
    void theMasterRenamesASiteAndChangesItsLanguages() throws Exception {
        mvc.perform(post("/__app/sites/alpha/edit").session(login("admin")).with(csrf())
                        .param("name", "  Alpha Renamed ").param("defaultLocale", "es").param("locales", "en, es, es"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "Updated site \"alpha\"."));
        Site edited = site("alpha");
        assertEquals("Alpha Renamed", edited.name(), "the name is trimmed and saved");
        assertEquals(ES, edited.defaultLocale(), "the default language changed");
        assertEquals(List.of(ES, EN), edited.locales(), "default first, duplicates dropped");
        assertEquals(List.of("alpha.test"), edited.hosts(), "hosts are untouched by a name/language edit");
        assertTrue(activity.tail(20).stream().anyMatch(l -> l.contains("site.updated") && l.contains("alpha")),
                "the edit is journaled");
    }

    @Test
    void aDefaultOutsideTheAvailableLanguagesIsRefusedAndNothingChanges() throws Exception {
        mvc.perform(post("/__app/sites/bravo/edit").session(login("admin")).with(csrf())
                        .param("name", "Changed").param("defaultLocale", "fr").param("locales", "en, es"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "The default language must be one of the available languages."));
        assertEquals("Site bravo", site("bravo").name(), "an invalid submit changes nothing — not even the name");
        assertEquals(List.of(EN), site("bravo").locales());
    }

    @Test
    void aBlankNameOrAMissingDefaultIsRefused() throws Exception {
        MockHttpSession admin = login("admin");
        mvc.perform(post("/__app/sites/charlie/edit").session(admin).with(csrf())
                        .param("name", "  ").param("defaultLocale", "en").param("locales", "en"))
                .andExpect(flash().attribute("error", "The site name cannot be blank."));
        mvc.perform(post("/__app/sites/charlie/edit").session(admin).with(csrf())
                        .param("name", "X").param("defaultLocale", "").param("locales", "en"))
                .andExpect(flash().attribute("error", "Choose a default language."));
        assertEquals("Site charlie", site("charlie").name());
    }

    @Test
    void anUnknownSiteIsAFlashErrorNotAServerError() throws Exception {
        mvc.perform(post("/__app/sites/ghost/edit").session(login("admin")).with(csrf())
                        .param("name", "X").param("defaultLocale", "en").param("locales", "en"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "No such site."));
        assertTrue(siteStore.find("ghost").isEmpty(), "no site is conjured up");
    }

    @Test
    void anOperatorIsForbiddenAndSeesNoEditForm() throws Exception {
        MockHttpSession op = login("operator");
        String html = mvc.perform(get("/__app/sites").session(op))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Site delta"), "the read-only list shows the sites");
        assertFalse(html.contains("/__app/sites/delta/edit"), "no edit affordance for a non-admin");
        mvc.perform(post("/__app/sites/delta/edit").session(op).with(csrf())
                        .param("name", "Hijacked").param("defaultLocale", "en").param("locales", "en"))
                .andExpect(status().isForbidden());
        assertEquals("Site delta", site("delta").name(), "unchanged");
    }

    @Test
    void anEditWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/__app/sites/echo/edit").session(login("admin"))
                        .param("name", "No token").param("defaultLocale", "en").param("locales", "en"))
                .andExpect(status().isForbidden());
        assertEquals("Site echo", site("echo").name(), "unchanged");
    }
}
