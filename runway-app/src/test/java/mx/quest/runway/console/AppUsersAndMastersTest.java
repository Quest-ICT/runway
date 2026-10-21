package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Set;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import java.util.Locale;
import mx.quest.runway.security.TenantMasterStore;
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

/** App-users CRUD (with the undeletable superadmin) and Site-masters (create/assign) — thin store wiring. */
@SpringBootTest
class AppUsersAndMastersTest {

    private static final String PASSWORD = "Users-Masters-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-fd");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        // Tenancies the master screen assigns against (create/assign now validate the tenancy exists).
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        SiteStore siteStore = new SiteStore(layout, new YamlStore());
        for (String id : new String[] {"acme", "beta", "solo"}) {
            siteStore.save(new Site(id, id, java.util.List.of(id + ".test"), Locale.ENGLISH,
                    java.util.List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        }
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AppUserStore appUsers;
    @Autowired
    private TenantMasterStore masters;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void createAnAppUserThenDeleteIt() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/users/create").session(master).with(csrf())
                        .param("username", "casey").param("password", "Casey-Pw-9").param("tier", "SYSTEMADMIN"))
                .andExpect(status().is3xxRedirection());
        User created = appUsers.findByUsername("casey").orElseThrow();
        assertTrue(created.roles().contains(Roles.SYSTEM_ADMIN), "created with the requested tier");

        mvc.perform(post("/__app/users/" + created.principalId() + "/delete").session(master).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(appUsers.findByUsername("casey").isEmpty(), "the user was deleted");
    }

    @Test
    void theApplicationSuperadminCannotBeDeleted() throws Exception {
        mvc.perform(post("/__app/users/app-master/delete").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(appUsers.findByPrincipalId("app-master").isPresent(), "the pinned superadmin is protected");
    }

    @Test
    void aNonMasterOperatorCannotCreateAppUsers() throws Exception {
        mvc.perform(post("/__app/users/create").session(sessionFor("operator")).with(csrf())
                        .param("username", "x").param("password", "p").param("tier", "SYSTEMOPERATOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createASiteMasterThenAssignAnotherTenancy() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/masters/create").session(master).with(csrf())
                        .param("username", "tboss").param("password", "Tenant-Boss-9").param("siteId", "acme"))
                .andExpect(status().is3xxRedirection());
        User provisioned = masters.findByUsername("tboss").orElseThrow();
        assertTrue(provisioned.assignedTo("acme"), "provisioned for its initial tenancy");

        mvc.perform(post("/__app/masters/assign").session(master).with(csrf())
                        .param("principalId", provisioned.principalId()).param("siteId", "beta"))
                .andExpect(status().is3xxRedirection());
        assertTrue(masters.findByUsername("tboss").orElseThrow().assignedTo("beta"), "assigned to a second tenancy");
    }

    @Test
    void aSiteMasterGetsARecoveryEmailAtCreationAndCanBeEditedLater() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/masters/create").session(master).with(csrf())
                        .param("username", "mailboss").param("password", "Mail-Boss-9").param("siteId", "acme")
                        .param("email", " boss@acme.test "))
                .andExpect(status().is3xxRedirection());
        User created = masters.findByUsername("mailboss").orElseThrow();
        assertEquals("boss@acme.test", created.recoveryEmail(), "the create form's email is kept, trimmed");

        mvc.perform(post("/__app/masters/" + created.principalId() + "/edit").session(master).with(csrf())
                        .param("displayName", "The Boss").param("email", "new@acme.test"))
                .andExpect(status().is3xxRedirection());
        User edited = masters.findByUsername("mailboss").orElseThrow();
        assertEquals("The Boss", edited.displayName());
        assertEquals("new@acme.test", edited.recoveryEmail());
        assertEquals(created.passwordHash(), edited.passwordHash(), "a profile edit never touches the password");
        assertEquals(created.sites(), edited.sites(), "nor the tenancies");

        mvc.perform(post("/__app/masters/" + created.principalId() + "/edit").session(master).with(csrf())
                        .param("displayName", "X").param("email", "not-an-address"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertEquals("new@acme.test", masters.findByUsername("mailboss").orElseThrow().recoveryEmail(),
                "an invalid email is refused, nothing saved");

        mvc.perform(post("/__app/masters/create").session(master).with(csrf())
                        .param("username", "badmail").param("password", "Bad-Mail-9").param("siteId", "acme")
                        .param("email", "nope"))
                .andExpect(flash().attributeExists("error"));
        assertTrue(masters.findByUsername("badmail").isEmpty(), "create refuses an invalid email too");

        mvc.perform(post("/__app/masters/" + created.principalId() + "/edit").session(sessionFor("operator"))
                        .with(csrf()).param("displayName", "Hijack").param("email", "h@x.test"))
                .andExpect(status().isForbidden());
        assertEquals("The Boss", masters.findByUsername("mailboss").orElseThrow().displayName());

        String page = mvc.perform(get("/__app/masters").session(master)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("/__app/masters/" + created.principalId() + "/edit"), "the row has its Edit form");
        assertTrue(page.contains("value=\"new@acme.test\""), "prefilled with the current email");
        assertTrue(page.contains("name=\"email\""), "the create form asks for the email");
    }

    @Test
    void assigningToANonExistentTenancyIsRejected() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/masters/create").session(master).with(csrf())
                .param("username", "assignee").param("password", "Tenant-Boss-9").param("siteId", "acme"))
                .andExpect(status().is3xxRedirection());
        String id = masters.findByUsername("assignee").orElseThrow().principalId();
        mvc.perform(post("/__app/masters/assign").session(master).with(csrf())
                .param("principalId", id).param("siteId", "ghost")).andExpect(status().is3xxRedirection());
        assertTrue(!masters.findByUsername("assignee").orElseThrow().assignedTo("ghost"),
                "a non-existent tenancy is not assigned");
    }

    @Test
    void deletingTheSoleSuperadminOfATenancyIsRejected() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/masters/create").session(master).with(csrf())
                .param("username", "solo").param("password", "Tenant-Boss-9").param("siteId", "solo"))
                .andExpect(status().is3xxRedirection());
        String id = masters.findByUsername("solo").orElseThrow().principalId();
        mvc.perform(post("/__app/masters/" + id + "/delete").session(master).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(masters.findByUsername("solo").isPresent(), "the sole superadmin of a tenancy is not deletable");
    }

    @Test
    void aDuplicateSuperadminUsernameIsRejected() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/masters/create").session(master).with(csrf())
                .param("username", "dupe").param("password", "Tenant-Boss-9").param("siteId", "acme"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__app/masters/create").session(master).with(csrf())
                .param("username", "dupe").param("password", "Other-Pw-9").param("siteId", "beta"))
                .andExpect(status().is3xxRedirection());
        long count = masters.list().stream().filter(m -> m.username().equals("dupe")).count();
        assertTrue(count == 1, "a duplicate tenant-superadmin username is refused");
    }

    @Test
    void theMastersScreenRenders() throws Exception {
        mvc.perform(get("/__app/masters").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("/__app/masters/create")));
    }
}
