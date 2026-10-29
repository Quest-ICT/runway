package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
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
 * The app-realm <b>Edit hosts</b> feature end to end, through the real security stack: an operator holding the
 * grantable {@code MANAGE_SITE_HOSTS} permission (here the app master, who passes it automatically) sees the
 * per-row host editor and can update a site's routing hosts (normalized + persisted); an operator WITHOUT the
 * grant sees no affordance and is denied at the request gate — proving the edit is delegable, not a coarse
 * master-only action. The render itself exercises the decoupled {@code sites.th.xml} (per-row modal keyed by
 * row index), so a binding error would fail here rather than 500 in production.
 */
@SpringBootTest
class AppSiteHostsScreenTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final String MASTER_PW = "Master-Secret-9";
    private static final String OPERATOR_PW = "Operator-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-hosts");
        var enc = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
        UserStore users = new UserStore(new YamlStore());
        Path appUsers = external.resolve("app-users.yml");
        // The pinned application master (passes every permission via the master short-circuit).
        users.save(appUsers, new User("app-master", "admin", enc.encode(MASTER_PW), "Admin", "a@x",
                true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // A plain operator: holds NO grantable permissions (so not even the Sites list).
        users.save(appUsers, new User("app-op", "operator", enc.encode(OPERATOR_PW), "Operator", "o@x",
                true, false, Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // A delegated host editor: MANAGE_SITE_HOSTS only — which also opens the list the host editor lives on.
        users.save(appUsers, new User("app-hosts", "hoster", enc.encode(OPERATOR_PW), "Hoster", "h@x",
                true, false, Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // Two sites so the two tests never touch the same routing state (order-independent).
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        SiteStore sites = new SiteStore(StorageLayout.from(props), new YamlStore());
        sites.save(new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("beta.test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        sites.save(new Site("gamma", "Gamma", List.of("gamma.test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        sites.save(new Site("delta", "Delta", List.of("delta.test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        // The default site: with runway.console.hosts unset, the console's reset links go to its address.
        sites.save(new Site("main", "Main", List.of("main.test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        new mx.quest.runway.platform.PlatformSettingsStore(StorageLayout.from(props), new YamlStore())
                .update(current -> current.withDefaultSiteId("main"));
        new GrantStore(StorageLayout.from(props), new YamlStore())
                .saveAppGrants(GrantFile.empty().withUserPermission("app-hosts", "MANAGE_SITE_HOSTS"));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteStore siteStore;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession login(String user, String password) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(user).password(password)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void masterSeesHostEditingAndCanUpdateHosts() throws Exception {
        MockHttpSession session = login("admin", MASTER_PW);

        String html = mvc.perform(get("/__app/sites").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The decoupled sites.th.xml rendered and the host-edit affordance is present for a permission holder.
        assertTrue(html.contains("Edit hosts"), "master sees the Edit hosts control");
        assertTrue(html.contains("/__app/sites/acme/hosts"), "the edit form targets the hosts endpoint");
        assertTrue(html.contains("si-hosts-modal-0"), "the per-row modal id rendered from the row index");

        // Master passes the MANAGE_SITE_HOSTS gate; the service normalizes (lowercase / trim / de-duplicate).
        mvc.perform(post("/__app/sites/acme/hosts").session(session).with(csrf())
                        .param("hosts", "New.Example.COM, acme.test , acme.test"))
                .andExpect(status().is3xxRedirection());
        assertEquals(List.of("new.example.com", "acme.test"), siteStore.find("acme").orElseThrow().hosts());
    }

    @Test
    void operatorWithoutTheGrantIsDeniedAndSeesNoAffordance() throws Exception {
        MockHttpSession session = login("operator", OPERATOR_PW);

        // Neither VIEW_TENANCIES nor MANAGE_SITE_HOSTS: the list itself is closed, and so is the routing edit.
        mvc.perform(get("/__app/sites").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/__app/sites/beta/hosts").session(session).with(csrf())
                        .param("hosts", "hijack.test"))
                .andExpect(status().isForbidden());
        assertEquals(List.of("beta.test"), siteStore.find("beta").orElseThrow().hosts(), "hosts unchanged");
    }

    @Test
    void aDelegatedHostEditorReachesTheListAndEditsHostsButNothingElse() throws Exception {
        MockHttpSession session = login("hoster", OPERATOR_PW);
        String html = mvc.perform(get("/__app/sites").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Edit hosts"), "MANAGE_SITE_HOSTS opens the list the host editor lives on");
        assertFalse(html.contains("/__app/sites/gamma/suspend") || html.contains("/__app/sites/gamma/delete")
                || html.contains("/__app/sites/create"), "no lifecycle affordance for a host editor");

        mvc.perform(post("/__app/sites/gamma/hosts").session(session).with(csrf()).param("hosts", "gamma.example"))
                .andExpect(status().is3xxRedirection());
        assertEquals(List.of("gamma.example"), siteStore.find("gamma").orElseThrow().hosts());
        mvc.perform(post("/__app/sites/gamma/suspend").session(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAPlatformAdminChangesTheDefaultSitesHostsOrBaseUrl() throws Exception {
        MockHttpSession hoster = login("hoster", OPERATOR_PW);
        mvc.perform(post("/__app/sites/main/hosts").session(hoster).with(csrf())
                        .param("hosts", "main.test").param("baseUrl", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__app/sites/main/hosts").session(hoster).with(csrf()).param("hosts", "evil.example"))
                .andExpect(status().isForbidden());
        Site main = siteStore.find("main").orElseThrow();
        assertEquals(List.of("main.test"), main.hosts());
        assertEquals(null, main.baseUrl());
        String html = mvc.perform(get("/__app/sites").session(hoster)).andReturn().getResponse().getContentAsString();
        assertFalse(html.contains("/__app/sites/main/hosts"), "no Edit hosts on the default site's row for them");
        assertTrue(html.contains("/__app/sites/gamma/hosts") || html.contains("/__app/sites/acme/hosts"),
                "the other rows keep it");

        MockHttpSession master = login("admin", MASTER_PW);
        mvc.perform(post("/__app/sites/main/hosts").session(master).with(csrf())
                        .param("hosts", "main.test, www.main.test"))
                .andExpect(status().is3xxRedirection());
        assertEquals(List.of("main.test", "www.main.test"), siteStore.find("main").orElseThrow().hosts());
    }

    @Test
    void theBaseUrlIsSetCheckedAndClearedInTheSameDialog() throws Exception {
        MockHttpSession session = login("admin", MASTER_PW);
        mvc.perform(post("/__app/sites/delta/hosts").session(session).with(csrf())
                        .param("hosts", "delta.test, www.delta.test").param("baseUrl", " HTTPS://WWW.Delta.test/ "))
                .andExpect(status().is3xxRedirection());
        Site saved = siteStore.find("delta").orElseThrow();
        assertEquals("https://www.delta.test", saved.baseUrl(), "lowercased, trailing slash dropped");
        assertEquals("https://www.delta.test", mx.quest.runway.site.SiteViews.toView(saved).baseUrl(),
                "absolute links use it instead of the first host");
        String html = mvc.perform(get("/__app/sites").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Links: https://www.delta.test"), "the row shows a base URL that is set");

        mvc.perform(post("/__app/sites/delta/hosts").session(session).with(csrf())
                        .param("hosts", "other.test").param("baseUrl", "https://delta.test/blog"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("error", "the base URL cannot have a path: a site is served at the root of its host"));
        assertEquals(List.of("delta.test", "www.delta.test"), siteStore.find("delta").orElseThrow().hosts(),
                "an invalid base URL saves nothing, not even the hosts");

        mvc.perform(post("/__app/sites/delta/hosts").session(session).with(csrf())
                        .param("hosts", "delta.test").param("baseUrl", ""))
                .andExpect(status().is3xxRedirection());
        assertEquals(null, siteStore.find("delta").orElseThrow().baseUrl(), "blank goes back to the derived base");

        mvc.perform(post("/__app/sites/delta/hosts").session(session).with(csrf()).param("hosts", "delta.test"))
                .andExpect(status().is3xxRedirection());
        assertEquals(null, siteStore.find("delta").orElseThrow().baseUrl(), "a form without the field keeps it");
    }
}
