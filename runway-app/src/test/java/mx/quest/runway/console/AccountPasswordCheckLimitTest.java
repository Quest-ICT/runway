package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.SiteUserStore;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The current-password check on the Account screen is limited, in both realms and on both forms: five wrong
 * guesses in a row in one session drop that session's sign-in (the kernel's own floor, no plugin needed); every wrong
 * guess is reported to the per-IP {@link LoginAttemptGate} and journaled; a locked IP is refused before any password
 * is checked; a right password resets the count. A counting gate stands in for the protection plugin, so the test
 * proves the forms reach the gate rather than inferring it from the absence of a lockout.
 */
@SpringBootTest
class AccountPasswordCheckLimitTest {

    private static final String PW = "Reverify-Limit-Secret-9";

    /** A gate that counts what reaches it and locks on demand. */
    static final class CountingGate implements LoginAttemptGate {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicBoolean locked = new AtomicBoolean();

        @Override
        public boolean isLocked(String clientIp) {
            return locked.get();
        }

        @Override
        public void recordFailure(String clientIp) {
            failures.incrementAndGet();
        }

        @Override
        public void recordSuccess(String clientIp) {
        }
    }

    @TestConfiguration
    static class Gate {
        @Bean
        @Primary
        CountingGate countingGate() {
            return new CountingGate();
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-reverify");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        Path appFile = external.resolve("app-users.yml");
        users.save(appFile, new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        for (String op : new String[] {"op1", "op2", "op3", "op4", "op5"}) {
            users.save(appFile, new User(op + "-id", op, hash, op, op + "@old.test", true, false, Realm.APPLICATION,
                    Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        }
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("ed1-id", "ed1", hash, "Ed", "", true,
                false, Realm.TENANT, Set.of(Roles.OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private AppUserStore appUsers;
    @Autowired
    private SiteUserStore siteUsers;
    @Autowired
    private AppActivityLog activity;
    @Autowired
    private CountingGate gate;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
        gate.locked.set(false);
        gate.failures.set(0);
    }

    private static RequestPostProcessor localhost() {
        return request -> {
            request.setServerName("localhost");
            return request;
        };
    }

    private MockHttpSession appLogin(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__app")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession siteLogin(String username) throws Exception {
        MvcResult result = mvc.perform(post("/login").with(localhost()).with(csrf())
                        .param("username", username).param("password", PW))
                .andExpect(redirectedUrl("/__admin")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void wrongEmailGuess(MockHttpSession session, String user) throws Exception {
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", user).param("recoveryEmail", "attacker@evil.test")
                        .param("currentPassword", "guess"))
                .andExpect(redirectedUrl("/__app/account"))
                .andExpect(flash().attribute("error", "The current password is incorrect."));
    }

    private long journaled(String user) {
        return activity.tail(200).stream()
                .filter(line -> line.contains("account.password-check-failed") && line.contains(user)).count();
    }

    @Test
    void fiveWrongGuessesOnTheEmailFormEndTheSession() throws Exception {
        MockHttpSession session = appLogin("op1");
        for (int i = 1; i < AccountSupport.MAX_WRONG_PASSWORDS; i++) {
            wrongEmailGuess(session, "op1");
        }
        assertEquals(AccountSupport.MAX_WRONG_PASSWORDS - 1, gate.failures.get(), "each guess reached the IP gate");
        assertEquals(AccountSupport.MAX_WRONG_PASSWORDS - 1, journaled("op1"), "each guess is journaled");
        assertTrue(activity.tail(200).stream().filter(line -> line.contains("account.password-check-failed")
                && line.contains("op1")).allMatch(line -> line.endsWith("\tip=127.0.0.1")),
                "with the client's IP: " + activity.tail(200));

        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "op1").param("recoveryEmail", "attacker@evil.test")
                        .param("currentPassword", "guess"))
                .andExpect(redirectedUrl("/app/login?reverify"));
        mvc.perform(get("/__app").session(session)).andExpect(status().is3xxRedirection());
        // The spent session cannot try again, even with the right password.
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "op1").param("recoveryEmail", "attacker@evil.test")
                        .param("currentPassword", PW))
                .andExpect(status().is3xxRedirection());
        assertEquals("op1@old.test", appUsers.findByPrincipalId("op1-id").orElseThrow().recoveryEmail());

        String page = mvc.perform(get("/app/login").param("reverify", "")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("too many wrong passwords on your account page"), "the sign-in page says why");
        appLogin("op1"); // the owner signs in again as usual
    }

    /** A session whose attributes may be read and written by several requests at once, as a server's session is. */
    static final class ConcurrentSession extends MockHttpSession {
        private final java.util.concurrent.ConcurrentHashMap<String, Object> attrs =
                new java.util.concurrent.ConcurrentHashMap<>();

        ConcurrentSession(MockHttpSession from) {
            for (String name : java.util.Collections.list(from.getAttributeNames())) {
                attrs.put(name, from.getAttribute(name));
            }
        }

        @Override
        public Object getAttribute(String name) {
            return attrs.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            if (value == null) {
                attrs.remove(name);
            } else {
                attrs.put(name, value);
            }
        }

        @Override
        public void removeAttribute(String name) {
            attrs.remove(name);
        }

        @Override
        public java.util.Enumeration<String> getAttributeNames() {
            return java.util.Collections.enumeration(new java.util.ArrayList<>(attrs.keySet()));
        }
    }

    /**
     * The count was read and written back by each request, so eight guesses sent at the same moment
     * were all checked (the documented floor is five). The count is now reserved before hashing.
     */
    @Test
    void guessesSentAtTheSameMomentAreHeldToTheLimit() throws Exception {
        ConcurrentSession session = new ConcurrentSession(appLogin("op5"));
        int burst = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(burst);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> guesses = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < burst; i++) {
                guesses.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                            .param("displayName", "op5").param("recoveryEmail", "attacker@evil.test")
                            .param("currentPassword", "guess")).andReturn();
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<?> guess : guesses) {
                guess.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(gate.failures.get() <= AccountSupport.MAX_WRONG_PASSWORDS,
                "at most five guesses were checked, not " + gate.failures.get());
        assertTrue(journaled("op5") <= AccountSupport.MAX_WRONG_PASSWORDS, "and journaled");
        mvc.perform(get("/__app").session(session)).andExpect(status().is3xxRedirection());
        assertEquals("op5@old.test", appUsers.findByPrincipalId("op5-id").orElseThrow().recoveryEmail());
    }

    @Test
    void aRightPasswordResetsTheCount() throws Exception {
        MockHttpSession session = appLogin("op2");
        for (int i = 1; i < AccountSupport.MAX_WRONG_PASSWORDS; i++) {
            wrongEmailGuess(session, "op2");
        }
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "op2").param("recoveryEmail", "op2@new.test")
                        .param("currentPassword", PW))
                .andExpect(flash().attribute("flash", "Your profile has been saved."));
        for (int i = 1; i < AccountSupport.MAX_WRONG_PASSWORDS; i++) {
            wrongEmailGuess(session, "op2");
        }
        mvc.perform(get("/__app").session(session)).andExpect(status().isOk());
    }

    @Test
    void aBlankPasswordIsAPromptNotAGuess() throws Exception {
        MockHttpSession session = appLogin("op3");
        for (int i = 0; i < AccountSupport.MAX_WRONG_PASSWORDS + 1; i++) {
            mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                            .param("displayName", "op3").param("recoveryEmail", "op3@new.test"))
                    .andExpect(flash().attribute("error",
                            "Enter your current password to change your recovery email."));
        }
        assertEquals(0, gate.failures.get());
        mvc.perform(get("/__app").session(session)).andExpect(status().isOk());
    }

    @Test
    void aLockedNetworkIsRefusedBeforeThePasswordIsChecked() throws Exception {
        MockHttpSession session = appLogin("op4");
        gate.locked.set(true);
        mvc.perform(post("/__app/account").session(session).with(csrf())
                        .param("currentPassword", PW).param("newPassword", "Another-Secret-77")
                        .param("confirmPassword", "Another-Secret-77"))
                .andExpect(redirectedUrl("/__app/account"))
                .andExpect(flash().attribute("error",
                        "Too many failed sign-in attempts from your network. Wait a few minutes, then try again."));
        assertTrue(PasswordEncoders.argon2id(PasswordStrength.STANDARD)
                .matches(PW, appUsers.findByPrincipalId("op4-id").orElseThrow().passwordHash()),
                "not changed, even with the right password");
    }

    @Test
    void theSitePasswordFormIsLimitedToo() throws Exception {
        MockHttpSession session = siteLogin("ed1");
        for (int i = 1; i < AccountSupport.MAX_WRONG_PASSWORDS; i++) {
            mvc.perform(post("/__admin/account").with(localhost()).session(session).with(csrf())
                            .param("currentPassword", "guess").param("newPassword", "Taken-Over-55")
                            .param("confirmPassword", "Taken-Over-55"))
                    .andExpect(redirectedUrl("/__admin/account"))
                    .andExpect(flash().attribute("error", "The current password is incorrect."));
        }
        mvc.perform(post("/__admin/account").with(localhost()).session(session).with(csrf())
                        .param("currentPassword", "guess").param("newPassword", "Taken-Over-55")
                        .param("confirmPassword", "Taken-Over-55"))
                .andExpect(redirectedUrl("/login?reverify"));
        assertEquals(AccountSupport.MAX_WRONG_PASSWORDS, gate.failures.get());
        mvc.perform(get("/__admin").with(localhost()).session(session)).andExpect(status().is3xxRedirection());
        User ed = siteUsers.list("acme").stream().filter(u -> u.username().equals("ed1")).findFirst().orElseThrow();
        assertTrue(PasswordEncoders.argon2id(PasswordStrength.STANDARD).matches(PW, ed.passwordHash()));
        String page = mvc.perform(get("/login").with(localhost()).param("reverify", ""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("too many wrong passwords on your account page"), "the sign-in page says why");
    }
}
