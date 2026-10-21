package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.log.AuditQuery;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The app-realm Activity → Logs screen ({@code /__app/logs}) and its CSV export: parsed, filtered, paged rows with
 * localized labels; gated by {@code VIEW_AUDIT} (master and wildcard admin pass, an ordinary operator only once
 * granted it); logged values escaped; and the newly journaled events (logout, permission changes) land in it.
 * Also a smoke test that the whole application context still wires with the journal threaded through every app
 * mutation controller.
 */
@SpringBootTest
class AppLogsScreenTest {

    private static final String PASSWORD = "App-Logs-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-applogs");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        users.save(file, new User("adm-user", "wildcard", hash, "Wildcard", "w@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_ADMIN), List.of()));
        users.save(file, new User("aud-user", "auditor", hash, "Auditor", "u@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AppActivityLog activity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String page(String url, String username) throws Exception {
        return mvc.perform(get(url).session(sessionFor(username))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static void appendDay(LocalDate day, String text) throws IOException {
        Path dir = Files.createDirectories(external.resolve("activity"));
        Files.writeString(dir.resolve("activity-" + day + ".log"), text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // --- rendering ---------------------------------------------------------------------------------

    @Test
    void theScreenShowsParsedRowsWithLocalizedLabelsInAListingTable() throws Exception {
        activity.record("admin", "site.created", "acme-demo-site");
        String html = page("/__app/logs", "admin");
        assertTrue(html.contains("data-listing-table=\"runway.app.audit\""), "a listing table");
        assertTrue(html.contains("Site created"), "the action is labelled");
        assertTrue(html.contains(">site.created<"), "the raw key is shown too (it is what the filter matches)");
        assertTrue(html.contains("acme-demo-site"));
        assertTrue(html.contains("href=\"/__app/logs/export.csv\""), "the CSV link carries the (empty) filter");
    }

    @Test
    void anUnknownActionIsShownAsItsRawKeyAndALegacyLineStillRenders() throws Exception {
        appendDay(LocalDate.now().minusDays(1), LocalDate.now().minusDays(1)
                + " 09:15:00\tlegacy-actor\tplugin-x.did-something\tbefore the upgrade\n");
        String html = page("/__app/logs?actor=legacy-actor", "admin");
        assertTrue(html.contains("plugin-x.did-something"));
        assertTrue(html.contains(LocalDate.now().minusDays(1) + " 09:15:00<"), "an offset-less time shows as written");
    }

    @Test
    void aLoggedValueIsEscapedNeverInterpretedAsMarkup() throws Exception {
        activity.record("admin", "site.created", "<script>alert('pwn')</script>");
        String html = page("/__app/logs?action=site.created", "admin");
        assertFalse(html.contains("<script>alert('pwn')"), "raw markup from a log line never reaches the page");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void theActorFilterNarrowsTheRows() throws Exception {
        activity.record("zed-filter-actor", "site.deleted", "zed-target");
        activity.record("other-filter-actor", "site.deleted", "other-target");
        String html = page("/__app/logs?actor=ZED-FILTER", "admin");
        assertTrue(html.contains("zed-target"));
        assertFalse(html.contains("other-target"), "another actor's row is filtered out");
        assertTrue(html.contains("value=\"ZED-FILTER\""), "the filter is echoed back into the form");
    }

    @Test
    void theDateFilterExcludesOtherDays() throws Exception {
        LocalDate old = LocalDate.now().minusDays(3);
        appendDay(old, old + "T10:00:00.000-06:00\tdate-actor\tsite.created\tthree-days-ago\n");
        activity.record("date-actor", "site.created", "today-row");
        String html = page("/__app/logs?actor=date-actor&from=" + LocalDate.now(), "admin");
        assertTrue(html.contains("today-row"));
        assertFalse(html.contains("three-days-ago"));
        String onlyOld = page("/__app/logs?actor=date-actor&from=" + old + "&to=" + old, "admin");
        assertTrue(onlyOld.contains("three-days-ago"));
        assertFalse(onlyOld.contains("today-row"));
    }

    @Test
    void pagingReachesBeyondTheFirstPage() throws Exception {
        StringBuilder lines = new StringBuilder();
        LocalDate day = LocalDate.now().minusDays(2);
        for (int i = 0; i < 150; i++) {
            lines.append(day).append(String.format("T10:%02d:%02d.000-06:00", i / 60, i % 60))
                    .append("\tpager\tsite.created\tpaged-").append(i).append('\n');
        }
        appendDay(day, lines.toString());
        String first = page("/__app/logs?actor=pager", "admin");
        assertTrue(first.contains("paged-149<"), "newest first");
        assertFalse(first.contains("paged-49<"), "only " + AuditView.PAGE_SIZE + " rows per page");
        assertTrue(first.contains("page=1"), "an Older link exists");
        String second = page("/__app/logs?actor=pager&page=1", "admin");
        assertTrue(second.contains("paged-0<"), "the oldest entry is reachable");
        assertFalse(second.contains("paged-149<"));
        assertTrue(second.contains("id=\"aj-prev\""), "a Newer link exists");
        assertFalse(second.contains("id=\"aj-next\""), "no Older link on the last page");
    }

    @Test
    void theActivityDropdownExposesTheLogsEntry() throws Exception {
        mvc.perform(get("/__app/logs").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("href=\"/__app/logs\"")));
    }

    @Test
    void theMisconfigurationWarningsRenderWhenUnconfigured() throws Exception {
        // This context configures no runway.security.secret-key and no platform SMTP, so both persistent
        // warning banners must render on every app-realm page (here, the Logs screen).
        mvc.perform(get("/__app/logs").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("runway.security.secret-key")))
                .andExpect(content().string(Matchers.containsString("id=\"op-warn-smtp\"")));
    }

    // --- the VIEW_AUDIT gate ----------------------------------------------------------------------

    @Test
    void anOrdinaryOperatorIsDeniedTheJournalAndItsExport() throws Exception {
        MockHttpSession op = sessionFor("operator");
        mvc.perform(get("/__app/logs").session(op)).andExpect(status().isForbidden());
        mvc.perform(get("/__app/logs/export.csv").session(op)).andExpect(status().isForbidden());
    }

    @Test
    void aWildcardAdministratorMayReadTheJournal() throws Exception {
        mvc.perform(get("/__app/logs").session(sessionFor("wildcard"))).andExpect(status().isOk());
        mvc.perform(get("/__app/logs/export.csv").session(sessionFor("wildcard"))).andExpect(status().isOk());
    }

    @Test
    void anAnonymousRequestIsSentToLogin() throws Exception {
        mvc.perform(get("/__app/logs/export.csv")).andExpect(status().is3xxRedirection());
    }

    @Test
    void grantingViewAuditOpensTheJournalAndTheGrantIsItselfJournaled() throws Exception {
        MockHttpSession auditor = sessionFor("auditor");
        mvc.perform(get("/__app/logs").session(auditor)).andExpect(status().isForbidden());

        mvc.perform(post("/__app/permissions/grant-permission").session(sessionFor("admin")).with(csrf())
                        .param("targetId", "aud-user").param("permission", "VIEW_AUDIT"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/__app/logs").session(sessionFor("auditor"))).andExpect(status().isOk());
        assertTrue(activity.query(new AuditQuery(null, null, "admin", "permissions.permission-granted"), 0, 10)
                .rows().stream().anyMatch(e -> e.detail().contains("user=aud-user")
                        && e.detail().contains("permission=VIEW_AUDIT")));
    }

    @Test
    void aRefusedMatrixChangeIsNotJournaled() throws Exception {
        mvc.perform(post("/__app/permissions/delete-role").session(sessionFor("admin")).with(csrf())
                        .param("roleName", Roles.SYSTEM_ADMIN)) // a reserved role — the service refuses
                .andExpect(status().is3xxRedirection());
        assertTrue(activity.query(new AuditQuery(null, null, null, "permissions.role-deleted"), 0, 10).rows()
                .isEmpty(), "nothing changed, so nothing is recorded");
    }

    // --- events -----------------------------------------------------------------------------------

    @Test
    void aLoginByTheAppRealmIsAudited() throws Exception {
        sessionFor("admin");
        assertTrue(page("/__app/logs?action=login.success", "admin").contains("Signed in"));
    }

    @Test
    void anAppLogoutIsAudited() throws Exception {
        MockHttpSession wildcard = sessionFor("wildcard");
        mvc.perform(post("/app/logout").session(wildcard).with(csrf())).andExpect(status().is3xxRedirection());
        assertTrue(activity.query(new AuditQuery(null, null, "wildcard", "logout"), 0, 10).rows().size() >= 1,
                "the sign-out was journaled");
    }

    // --- CSV --------------------------------------------------------------------------------------

    @Test
    void theCsvExportCarriesTheFilteredRowsSafely() throws Exception {
        activity.record("=csv-actor", "site.created", "csv-detail");
        activity.record("other-csv-actor", "site.created", "not-in-export");
        MockHttpServletResponse response = mvc.perform(get("/__app/logs/export.csv").param("actor", "=csv-actor")
                        .session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(response.getContentType().startsWith("text/csv"));
        assertTrue(response.getHeader("Content-Disposition").contains("attachment"));
        assertTrue(body.startsWith("﻿\"time\",\"actor\",\"action\",\"label\",\"detail\""), body);
        assertTrue(body.contains("\"'=csv-actor\",\"site.created\",\"Site created\",\"csv-detail\""), body);
        assertFalse(body.contains("not-in-export"), "the export honours the filter");
        assertEquals(2, body.split("\r\n").length, "a header and exactly one row");
    }
}
