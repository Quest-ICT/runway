package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContact;
import mx.quest.runway.site.SiteContactService;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteParamsService;
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

/**
 * The tenant Site-config screen ({@code /__admin/config}). Proves the screen renders and is
 * CONFIGURE_SITE-gated; that saving name/languages/contact/params persists to the <b>bound</b> site only
 * (a second site is untouched — isolation); that a default locale not in the available set and a blank name
 * are rejected; that a template param round-trips; and that hosts/id are not editable via this route.
 *
 * <p>The bound tenancy is {@code acme} (host {@code localhost}, which MockMvc requests carry). A second site
 * {@code other} (host {@code other.test}) exists only to prove isolation — this route can never touch it.
 */
@SpringBootTest
class SiteConfigScreenTest {

    private static final String PW = "Config-Screen-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-2-1");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), null, SiteStatus.ACTIVE));
        sites.save(new Site("other", "Other", List.of("other.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));

        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path acmeUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(acmeUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));      // wildcard → has CONFIGURE_SITE
        users.save(acmeUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
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
    private SiteStore siteStore;
    @Autowired
    private SiteContactService contacts;
    @Autowired
    private SiteParamsService siteParams;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        // Sites share the class's external dir, so reset the bound site (acme) to a known baseline before each
        // test — name "Acme", locales [en, es] default en, no contact, no params — so a test's assertions do
        // not depend on the (undefined) order the mutating tests run in.
        siteStore.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), null, SiteStatus.ACTIVE));
        contacts.save("acme", SiteContact.EMPTY);
        siteParams.save("acme", java.util.Map.of());

        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void theFormRendersWithTheBoundSitesValues() throws Exception {
        mvc.perform(get("/__admin/config").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/config\"")))
                .andExpect(content().string(Matchers.containsString("name=\"name\"")))
                .andExpect(content().string(Matchers.containsString("name=\"contactEmail\"")))
                .andExpect(content().string(Matchers.containsString("name=\"paramKey\"")))
                .andExpect(content().string(Matchers.containsString("name=\"defaultLocale\"")))
                // Pre-filled with the bound site's current name (the per-test baseline).
                .andExpect(content().string(Matchers.containsString("value=\"Acme\"")))
                // How the site is reached and linked, read-only: the operator sets both on Sites.
                .andExpect(content().string(Matchers.containsString("id=\"cfg-hosts\">localhost<")))
                .andExpect(content().string(Matchers.containsString("https://localhost")))
                .andExpect(content().string(Matchers.containsString("The first address, over https.")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"baseUrl\""))));
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSeeTheScreen() throws Exception {
        mvc.perform(get("/__admin/config").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }

    @Test
    void savingPersistsNameLanguagesContactAndParamsToTheBoundSite() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "Acme Rebranded")
                        .param("defaultLocale", "es")
                        .param("locale", "en", "es", "fr")   // available set (default es is included)
                        .param("contactEmail", "hi@acme.test")
                        .param("contactPhone", "555-0100")
                        .param("contactAddress", "1 Market St")
                        .param("paramKey", "brandColor", "")
                        .param("paramValue", "#0af", ""))
                .andExpect(status().is3xxRedirection())
                // changing the default locale (en → es) warns that page URL paths may no longer match the content
                .andExpect(flash().attribute("flash", Matchers.containsString("default language")));

        Site acme = siteStore.find("acme").orElseThrow();
        assertEquals("Acme Rebranded", acme.name());
        assertEquals(Locale.forLanguageTag("es"), acme.defaultLocale());
        assertTrue(acme.locales().contains(Locale.forLanguageTag("fr")), "the added language is offered");
        assertEquals(Locale.forLanguageTag("es"), acme.locales().get(0), "the default is first");

        assertEquals("hi@acme.test", contacts.get("acme").email());
        assertEquals("555-0100", contacts.get("acme").phone());
        assertEquals("#0af", siteParams.get("acme").get("brandColor"));
    }

    @Test
    void savingLeavesASecondSiteEntirelyUntouched() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "Changed")
                        .param("defaultLocale", "en")
                        .param("locale", "en")
                        .param("contactEmail", "x@acme.test")
                        .param("paramKey", "k").param("paramValue", "v"))
                .andExpect(status().is3xxRedirection());

        // The bound site was 'acme' (host localhost); 'other' must be byte-for-byte its original self.
        Site other = siteStore.find("other").orElseThrow();
        assertEquals("Other", other.name());
        assertEquals(List.of("other.test"), other.hosts());
        assertEquals(List.of(Locale.ENGLISH), other.locales());
        assertTrue(contacts.get("other").isEmpty(), "the other site has no contact file");
        assertTrue(siteParams.get("other").isEmpty(), "the other site has no params");
    }

    @Test
    void aDefaultLocaleNotInTheAvailableSetIsRejected() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "Acme")
                        .param("defaultLocale", "de")     // not among the ticked locales
                        .param("locale", "en", "es"))
                .andExpect(status().is3xxRedirection());

        // Nothing changed — the pre-existing default (en) and name stand.
        Site acme = siteStore.find("acme").orElseThrow();
        assertEquals(Locale.ENGLISH, acme.defaultLocale());
        assertEquals("Acme", acme.name());
        assertFalse(acme.locales().contains(Locale.GERMAN), "the rejected default was never added");
    }

    @Test
    void aBlankNameIsRejected() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "   ")
                        .param("defaultLocale", "en")
                        .param("locale", "en"))
                .andExpect(status().is3xxRedirection());

        assertEquals("Acme", siteStore.find("acme").orElseThrow().name(), "a blank name never persists");
    }

    @Test
    void aParamRoundTripsThroughTheForm() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "Acme")
                        .param("defaultLocale", "en")
                        .param("locale", "en")
                        .param("paramKey", "analyticsId")
                        .param("paramValue", "UA-123"))
                .andExpect(status().is3xxRedirection());
        assertEquals("UA-123", siteParams.get("acme").get("analyticsId"));

        // It pre-fills on the next render (proves the .th.xml value binding on the param rows works).
        mvc.perform(get("/__admin/config").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("value=\"analyticsId\"")))
                .andExpect(content().string(Matchers.containsString("value=\"UA-123\"")));
    }

    @Test
    void hostsAndIdCannotBeChangedViaThisRoute() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        // Even if a crafted POST carries host/id params, the controller ignores them and preserves routing.
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "Acme")
                        .param("defaultLocale", "en")
                        .param("locale", "en")
                        .param("hosts", "evil.test")
                        .param("id", "hijacked"))
                .andExpect(status().is3xxRedirection());

        Site acme = siteStore.find("acme").orElseThrow();
        assertEquals(List.of("localhost"), acme.hosts(), "hosts stay app-controlled");
        assertEquals("acme", acme.id(), "the id is immutable");
        assertTrue(siteStore.find("hijacked").isEmpty(), "no site was created under a forged id");
    }
}
