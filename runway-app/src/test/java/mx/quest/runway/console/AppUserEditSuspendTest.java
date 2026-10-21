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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.security.AppUserStore;
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
 * App-user <b>edit</b> (profile + tier) and <b>suspend / resume</b>, through the real security stack: the guards
 * (pinned superadmin, self, valid tier, non-admin 403), sign-in refused while suspended, and — via
 * {@code AccountStateFilter} — an open session that loses its rights on its very next request (suspended, deleted)
 * or gains/loses them (tier change) without signing in again. Each test touches its own users.
 */
@SpringBootTest
class AppUserEditSuspendTest {

    private static final String PW = "Edit-Suspend-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-appuser-edit");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // A non-master wildcard administrator (passes the platform gate, but is not the pin).
        users.save(file, new User("boss", "boss", hash, "Boss", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_ADMIN), List.of()));
        for (String op : new String[] {"op1", "op2", "op3", "op4", "op5", "op6", "op7", "op8"}) {
            users.save(file, new User(op, op, hash, op, "", true, false, Realm.APPLICATION,
                    Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        }
        // An id that differs from the username, so a journal entry naming one cannot pass for the other.
        users.save(file, new User("del-0b9e", "leaver", hash, "Leaver", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AppUserStore appUsers;
    @Autowired
    private AppActivityLog activity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__app")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private User user(String id) {
        return appUsers.findByPrincipalId(id).orElseThrow();
    }

    private boolean journaled(String action, String detail) {
        return activity.tail(50).stream().anyMatch(l -> l.contains(action) && l.contains(detail));
    }

    // --- edit -------------------------------------------------------------------------------------------

    @Test
    void theMasterEditsAUsersProfile() throws Exception {
        mvc.perform(post("/__app/users/op1/edit").session(login("admin")).with(csrf())
                        .param("displayName", " Operator One ").param("email", "one@example.com")
                        .param("tier", "SYSTEMOPERATOR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "Updated user \"op1\"."));
        User edited = user("op1");
        assertEquals("Operator One", edited.displayName());
        assertEquals("one@example.com", edited.recoveryEmail());
        assertEquals(Set.of(Roles.SYSTEM_OPERATOR), edited.roles(), "same tier: roles untouched");
        assertEquals("op1", edited.username(), "the login name never changes here");
        assertTrue(journaled("appuser.updated", "op1"), "the edit is journaled");
    }

    @Test
    void anInvalidEmailOrAnOverlongNameIsRefusedAndNothingChanges() throws Exception {
        MockHttpSession admin = login("admin");
        mvc.perform(post("/__app/users/op1/edit").session(admin).with(csrf())
                        .param("displayName", "Whatever").param("email", "not an email"))
                .andExpect(flash().attribute("error", "That is not a valid email address."));
        mvc.perform(post("/__app/users/op1/edit").session(admin).with(csrf())
                        .param("displayName", "x".repeat(129)).param("email", ""))
                .andExpect(flash().attribute("error", "The display name is too long (128 characters at most)."));
        assertFalse(user("op1").displayName().equals("Whatever"), "a refused edit wrote nothing");
    }

    @Test
    void aTierChangeAppliesToTheTargetsOpenSessionOnItsNextRequest() throws Exception {
        MockHttpSession op2 = login("op2");
        mvc.perform(get("/__app/settings").session(op2)).andExpect(status().isForbidden());

        MockHttpSession admin = login("admin");
        mvc.perform(post("/__app/users/op2/edit").session(admin).with(csrf()).param("tier", "SYSTEMADMIN"))
                .andExpect(status().is3xxRedirection());
        assertEquals(Set.of(Roles.SYSTEM_ADMIN), user("op2").roles(), "promoted: the old tier is replaced");
        mvc.perform(get("/__app/settings").session(op2))
                .andExpect(status().isOk()); // same session, no re-login: the principal was refreshed

        mvc.perform(post("/__app/users/op2/edit").session(admin).with(csrf()).param("tier", "SYSTEMOPERATOR"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/__app/settings").session(op2)).andExpect(status().isForbidden()); // demotion is live too
    }

    @Test
    void aTierOutsideTheRealmsTiersIsRefused() throws Exception {
        mvc.perform(post("/__app/users/op8/edit").session(login("admin")).with(csrf())
                        .param("tier", Roles.SYSTEM_SUPERADMIN))
                .andExpect(flash().attribute("error", "Invalid access tier."));
        assertEquals(Set.of(Roles.SYSTEM_OPERATOR), user("op8").roles(), "no second superadmin can be minted");
    }

    // --- suspend / resume -------------------------------------------------------------------------------

    @Test
    void aSuspendedUserIsSignedOutCannotSignInAndCanBeResumed() throws Exception {
        MockHttpSession op3 = login("op3");
        mvc.perform(get("/__app").session(op3)).andExpect(status().isOk());

        MockHttpSession admin = login("admin");
        mvc.perform(post("/__app/users/op3/suspend").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "Suspended user \"op3\"."));
        assertFalse(user("op3").enabled(), "the record is disabled");
        assertTrue(journaled("appuser.suspended", "op3"), "suspension is journaled");

        // The open session is dropped on its next request (redirected to the login page, not served).
        mvc.perform(get("/__app").session(op3))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login"));
        // And a fresh sign-in with the right password is refused with the generic error.
        mvc.perform(formLogin("/app/login").user("op3").password(PW)).andExpect(redirectedUrl("/app/login?error"));

        // The list shows the state and offers Resume.
        String html = mvc.perform(get("/__app/users").session(admin)).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__app/users/op3/resume\""), "a suspended user offers Resume");
        assertFalse(html.contains("action=\"/__app/users/op3/suspend\""), "and no second Suspend");

        mvc.perform(post("/__app/users/op3/resume").session(admin).with(csrf()))
                .andExpect(flash().attribute("flash", "Resumed user \"op3\"."));
        assertTrue(user("op3").enabled());
        assertTrue(journaled("appuser.resumed", "op3"));
        login("op3"); // asserts the redirect to /__app — sign-in works again, same password
    }

    @Test
    void aDeletedUsersOpenSessionIsDroppedToo() throws Exception {
        MockHttpSession op7 = login("op7");
        mvc.perform(post("/__app/users/op7/delete").session(login("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/__app").session(op7)).andExpect(redirectedUrl("/app/login"));
    }

    @Test
    void aDeletionIsJournaledByUsernameWithTheIdAlongside() throws Exception {
        mvc.perform(post("/__app/users/del-0b9e/delete").session(login("admin")).with(csrf()))
                .andExpect(flash().attribute("flash", "Deleted the user."));
        assertTrue(activity.tail(100).stream().anyMatch(l -> l.contains("\tappuser.deleted\tleaver (id del-0b9e)")),
                "the journal names the user, not only the opaque id: " + activity.tail(5));
    }

    @Test
    void aWildcardAdminMaySuspendAnOperator() throws Exception {
        mvc.perform(post("/__app/users/op6/suspend").session(login("boss")).with(csrf()))
                .andExpect(flash().attribute("flash", "Suspended user \"op6\"."));
        assertFalse(user("op6").enabled());
    }

    // --- guards -----------------------------------------------------------------------------------------

    @Test
    void thePinnedSuperadminCanNeverBeSuspendedOrReTieredButItsProfileIsEditable() throws Exception {
        MockHttpSession boss = login("boss");
        mvc.perform(post("/__app/users/app-master/suspend").session(boss).with(csrf()))
                .andExpect(flash().attribute("error", "The application superadmin cannot be suspended."));
        mvc.perform(post("/__app/users/app-master/edit").session(boss).with(csrf()).param("tier", "SYSTEMOPERATOR"))
                .andExpect(flash().attribute("error", "The application superadmin's access tier cannot be changed."));
        User pin = user("app-master");
        assertTrue(pin.enabled(), "still enabled");
        assertEquals(Set.of(Roles.SYSTEM_SUPERADMIN), pin.roles(), "still the superadmin");

        mvc.perform(post("/__app/users/app-master/edit").session(login("admin")).with(csrf())
                        .param("displayName", "The Root").param("email", "root@example.com"))
                .andExpect(flash().attribute("flash", "Updated user \"admin\"."));
        assertEquals("The Root", user("app-master").displayName(), "the pin's profile is editable");
        mvc.perform(get("/__app").session(login("admin"))).andExpect(status().isOk());
    }

    @Test
    void anAdminCannotSuspendOrReTierThemselves() throws Exception {
        MockHttpSession boss = login("boss");
        mvc.perform(post("/__app/users/boss/suspend").session(boss).with(csrf()))
                .andExpect(flash().attribute("error", "You cannot suspend your own account."));
        mvc.perform(post("/__app/users/boss/edit").session(boss).with(csrf()).param("tier", "SYSTEMOPERATOR"))
                .andExpect(flash().attribute("error", "You cannot change your own access tier."));
        assertTrue(user("boss").enabled());
        assertEquals(Set.of(Roles.SYSTEM_ADMIN), user("boss").roles());
    }

    @Test
    void anOperatorIsForbiddenFromEveryNewAction() throws Exception {
        MockHttpSession op4 = login("op4");
        mvc.perform(post("/__app/users/op5/edit").session(op4).with(csrf()).param("displayName", "pwned"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__app/users/op5/suspend").session(op4).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/__app/users/op5/resume").session(op4).with(csrf())).andExpect(status().isForbidden());
        assertEquals("op5", user("op5").displayName());
        assertTrue(user("op5").enabled());
    }

    @Test
    void suspendWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/__app/users/op5/suspend").session(login("admin"))).andExpect(status().isForbidden());
        assertTrue(user("op5").enabled());
    }

    @Test
    void anUnknownUserIsAFlashError() throws Exception {
        mvc.perform(post("/__app/users/nobody/suspend").session(login("admin")).with(csrf()))
                .andExpect(flash().attribute("error", "No such user."));
    }

    @Test
    void theListRendersEditModalsAndHidesSelfAndPinActions() throws Exception {
        String html = mvc.perform(get("/__app/users").session(login("boss")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__app/users/op1/edit\""), "every row has an edit form");
        assertTrue(html.contains("us-edit-modal-0"), "modal ids are unique per row");
        assertTrue(html.contains("action=\"/__app/users/op1/suspend\""), "an operator row offers Suspend");
        assertFalse(html.contains("action=\"/__app/users/boss/suspend\""), "no Suspend on your own row");
        assertFalse(html.contains("action=\"/__app/users/app-master/suspend\""), "no Suspend on the pin");
    }
}
