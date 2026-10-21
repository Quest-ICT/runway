package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
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
 * The permissions matrix end to end (application realm) and the realm-isolation invariant: the
 * matrix is driven by the logged-in actor's realm, so an application operator writes the application stores and
 * can never reach the tenant matrix (its route lives only on the tenant chain).
 */
@SpringBootTest
class PermissionsMatrixTest {

    private static final String PASSWORD = "Perm-Matrix-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-9g");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
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
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Files.deleteIfExists(external.resolve("app-grants.yml")); // isolate the shared external dir per test
        Files.deleteIfExists(external.resolve("app-roles.yml"));  // …including any custom roles from a sibling test
    }

    private MockHttpSession master() throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user("admin").password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void theAppMatrixRendersUsersRolesAndActions() throws Exception {
        String html = mvc.perform(get("/__app/permissions").session(master()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("rw-topbar-app"), "rendered through the operator shell");
        assertTrue(html.contains(">operator<"), "the app users are listed");
        assertTrue(html.contains("/__app/permissions/assign-role"), "the assign-role form posts to the app route");
        assertTrue(html.contains("/__app/permissions/role-grant"), "the role-delta form is present");
    }

    @Test
    void assigningARoleWritesTheApplicationGrantStore() throws Exception {
        mvc.perform(post("/__app/permissions/assign-role").session(master()).with(csrf())
                        .param("targetId", "op-user").param("roleName", Roles.SYSTEM_ADMIN))
                .andExpect(status().is3xxRedirection());
        String grants = Files.readString(external.resolve("app-grants.yml"));
        assertTrue(grants.contains("op-user"), "the assignment landed in app-grants.yml (the application scope)");
        assertTrue(grants.contains(Roles.SYSTEM_ADMIN));
    }

    @Test
    void theWildcardRoleShowsAllPermissionsNotAnEmptyRow() throws Exception {
        // The display fix: the all-access wildcard tier (SYSTEMADMIN) must render as holding everything, not
        // the old empty row (which listed only plugin-default control points and so inverted apparent power).
        String html = mvc.perform(get("/__app/permissions").session(master()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("All permissions"), "the wildcard row is labelled all-access, was not empty");
    }

    @Test
    void aSuccessfulMatrixActionConfirmsWithAFlashMessage() throws Exception {
        // Issue: matrix writes used to redirect silently. Every mutation now sets a success flash.
        mvc.perform(post("/__app/permissions/role-grant").session(master()).with(csrf())
                        .param("roleName", Roles.SYSTEM_OPERATOR).param("permission", "VIEW_APP_DASHBOARD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("flash"));
    }

    @Test
    void aRoleDeltaThroughTheMatrixWritesTheApplicationGrantStore() throws Exception {
        mvc.perform(post("/__app/permissions/role-grant").session(master()).with(csrf())
                        .param("roleName", Roles.SYSTEM_OPERATOR).param("permission", "VIEW_APP_DASHBOARD"))
                .andExpect(status().is3xxRedirection());
        String grants = Files.readString(external.resolve("app-grants.yml"));
        assertTrue(grants.contains("VIEW_APP_DASHBOARD"), "the role delta landed in app-grants.yml");
    }

    @Test
    void aNonAdminOperatorCannotReadTheMatrix() throws Exception {
        // The whole matrix (every user's roles/grants) is sensitive; a read-mostly operator must be denied.
        MvcResult login = mvc.perform(formLogin("/app/login").user("operator").password(PASSWORD)).andReturn();
        MockHttpSession op = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/__app/permissions").session(op)).andExpect(status().isForbidden());
    }

    @Test
    void creatingARolePersistsItAndItAppearsInTheMatrix() throws Exception {
        MockHttpSession session = master();
        mvc.perform(post("/__app/permissions/create-role").session(session).with(csrf())
                        .param("name", "REPORTER"))
                .andExpect(status().is3xxRedirection());
        String roles = Files.readString(external.resolve("app-roles.yml"));
        assertTrue(roles.contains("REPORTER"), "the new role landed in app-roles.yml");
        String html = mvc.perform(get("/__app/permissions").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains(">REPORTER<"), "the new role shows in the matrix");
        assertTrue(html.contains("/__app/permissions/create-role"), "the create-role form is present");
        assertTrue(html.contains("/__app/permissions/add-inherit"), "the hierarchy form is present");
    }

    @Test
    void aDuplicateRoleNameIsRejectedAsAFlashError() throws Exception {
        MockHttpSession session = master();
        mvc.perform(post("/__app/permissions/create-role").session(session).with(csrf()).param("name", "REPORTER"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__app/permissions/create-role").session(session).with(csrf()).param("name", "REPORTER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));
    }

    @Test
    void addingAHierarchyEdgeWorksAndACycleIsRejected() throws Exception {
        MockHttpSession session = master();
        mvc.perform(post("/__app/permissions/create-role").session(session).with(csrf()).param("name", "AA"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__app/permissions/create-role").session(session).with(csrf())
                        .param("name", "BB").param("inherits", "AA"))
                .andExpect(status().is3xxRedirection()); // BB → AA
        // AA → BB would close the loop: rejected, surfaced as a flash error, nothing persisted.
        mvc.perform(post("/__app/permissions/add-inherit").session(session).with(csrf())
                        .param("roleName", "AA").param("parentName", "BB"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));
    }

    @Test
    void deletingARoleWorksAndAReservedRoleCannotBeDeleted() throws Exception {
        MockHttpSession session = master();
        mvc.perform(post("/__app/permissions/create-role").session(session).with(csrf()).param("name", "GONE"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__app/permissions/delete-role").session(session).with(csrf()).param("roleName", "GONE"))
                .andExpect(status().is3xxRedirection());
        assertTrue(!Files.readString(external.resolve("app-roles.yml")).contains("GONE"), "the role was deleted");
        // A reserved role key cannot be deleted (flash error).
        mvc.perform(post("/__app/permissions/delete-role").session(session).with(csrf())
                        .param("roleName", Roles.SYSTEM_ADMIN))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));
    }

    @Test
    void roleManagementRoutesAreGatedForANonAdmin() throws Exception {
        // A read-mostly operator passes the coarse request gate but must be denied (403) at the mutation.
        MvcResult login = mvc.perform(formLogin("/app/login").user("operator").password(PASSWORD)).andReturn();
        MockHttpSession op = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(post("/__app/permissions/create-role").session(op).with(csrf()).param("name", "NOPE"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__app/permissions/delete-role").session(op).with(csrf()).param("roleName", "x"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__app/permissions/add-inherit").session(op).with(csrf())
                        .param("roleName", "x").param("parentName", "y"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anApplicationActorCannotReachTheTenantMatrix() throws Exception {
        // The tenant matrix lives only on the tenant security chain; an application session does not authenticate
        // there, so it is funneled to the tenant login — cross-realm driving is impossible by construction.
        mvc.perform(get("/__admin/permissions").session(master()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
