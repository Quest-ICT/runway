package mx.quest.runway.console;

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
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.SiteUserStore;
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

/** The tenant-realm Pages list + Users CRUD, driven by a tenant admin over the bound tenancy (host localhost). */
@SpringBootTest
class TenantScreensTest {

    private static final String TENANT_PW = "Tenant-Screens-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-ff");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(TENANT_PW);
        users.save(layout.siteDir("acme").resolve("users.yml"),
                new User("acme-admin", "admin", hash, "Acme Admin", "", true, false, Realm.TENANT,
                        Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteUserStore siteUsers;
    @Autowired
    private mx.quest.runway.authz.GrantStore grants;
    @Autowired
    private mx.quest.runway.site.SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Add the SiteContextFilter so the bound site is resolved BEFORE the tenant security chain (the tenant
        // realm authenticates against the bound site's user store) — as in TenantRealmSecurityTest.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new mx.quest.runway.site.SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession tenantSession() throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user("admin").password(TENANT_PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void thePagesListRendersForTheBoundTenancy() throws Exception {
        mvc.perform(get("/__admin/pages").session(tenantSession()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("data-listing-table=\"runway.admin.pages\"")));
    }

    @Autowired
    private org.thymeleaf.spring6.SpringTemplateEngine templateEngine;
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void thePagesBadgeRendersForPublishedAndDraftPagesWithoutCrashing() {
        // The FF list test had zero pages, so the badge binding (#{} branches) was never evaluated. With real
        // pages it must NOT crash (a #{} may not nest inside a ${} ternary).
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest("GET", "/__admin/pages")));
        try {
            mx.quest.runway.page.Page published = new mx.quest.runway.page.Page("home", "/", java.util.Map.of(),
                    List.of(Locale.ENGLISH), java.util.Map.of(), java.util.Map.of(), null, null, false, false, null, null);
            mx.quest.runway.page.Page draft = new mx.quest.runway.page.Page("about", "/about", java.util.Map.of(),
                    List.of(Locale.ENGLISH), java.util.Map.of(), java.util.Map.of(), null, null, true, false, null, null);
            org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context(Locale.ENGLISH);
            ctx.setVariable("pages", List.of(published, draft));
            // The editorial status the controller derives per page, and whether Enable is offered.
            ctx.setVariable("statuses", java.util.Map.of(
                    "home", new mx.quest.runway.editorial.PageStatus(true, null, false),
                    "about", new mx.quest.runway.editorial.PageStatus(false, null, false)));
            ctx.setVariable("canPublish", true);
            ctx.setVariable(org.thymeleaf.spring6.expression.ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new org.thymeleaf.spring6.expression.ThymeleafEvaluationContext(applicationContext,
                            org.springframework.core.convert.support.DefaultConversionService.getSharedInstance()));
            String html = templateEngine.process("operator/admin/pages", ctx);
            assertTrue(html.contains("Published"), "the published badge resolved (no #{}-in-${} crash)");
            assertTrue(html.contains("Draft"), "the draft badge resolved");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void theConflictsPanelRendersBothKindsAndHidesWhenEmpty() {
        // The visibility triple's site-wide leg: a PAGE_SHADOWS_CONTENT row (winner + claimants + the localized
        // consequence), a CONTENT_CLASH row (all claimants), and NO panel at all when conflicts is empty.
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest("GET", "/__admin/pages")));
        try {
            var shadowed = new mx.quest.runway.content.PathOwnership.Conflict("/about", "en", "about",
                    List.of("blog:my-post"), mx.quest.runway.content.PathOwnership.Kind.PAGE_SHADOWS_CONTENT);
            var clash = new mx.quest.runway.content.PathOwnership.Conflict("/deal", "en", null,
                    List.of("blog:sale", "shop:sale"), mx.quest.runway.content.PathOwnership.Kind.CONTENT_CLASH);
            org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context(Locale.ENGLISH);
            ctx.setVariable("pages", List.<mx.quest.runway.page.Page>of());
            ctx.setVariable("conflicts", List.of(shadowed, clash));
            ctx.setVariable(org.thymeleaf.spring6.expression.ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new org.thymeleaf.spring6.expression.ThymeleafEvaluationContext(applicationContext,
                            org.springframework.core.convert.support.DefaultConversionService.getSharedInstance()));
            String html = templateEngine.process("operator/admin/pages", ctx);
            assertTrue(html.contains("URL conflicts"), "the panel renders while conflicts exist");
            assertTrue(html.contains("/about") && html.contains("blog:my-post"),
                    "the shadowed row names the path and the claimant");
            assertTrue(html.contains("about") && html.contains("wins"),
                    "the page-wins consequence sentence resolved with the winner named");
            assertTrue(html.contains("blog:sale, shop:sale"), "the clash row names ALL claimants");

            // And the empty case: the panel must not exist at all.
            org.thymeleaf.context.Context empty = new org.thymeleaf.context.Context(Locale.ENGLISH);
            empty.setVariable("pages", List.<mx.quest.runway.page.Page>of());
            empty.setVariable("conflicts", List.of());
            empty.setVariable(org.thymeleaf.spring6.expression.ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new org.thymeleaf.spring6.expression.ThymeleafEvaluationContext(applicationContext,
                            org.springframework.core.convert.support.DefaultConversionService.getSharedInstance()));
            assertTrue(!templateEngine.process("operator/admin/pages", empty).contains("id=\"pg-conflicts\""),
                    "no conflicts, no panel element (the source comment mentions the words — check the id)");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void aTenantAdminCreatesAndDeletesASubUser() throws Exception {
        MockHttpSession admin = tenantSession();
        mvc.perform(post("/__admin/users/create").session(admin).with(csrf())
                        .param("username", "editor").param("password", "Editor-Pw-9").param("tier", "OPERATOR"))
                .andExpect(status().is3xxRedirection());
        User created = siteUsers.findByUsername("acme", "editor").orElseThrow();
        assertTrue(created.roles().contains(Roles.OPERATOR), "created in the bound tenancy with the tier");

        mvc.perform(post("/__admin/users/" + created.principalId() + "/delete").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(siteUsers.findByUsername("acme", "editor").isEmpty(), "the sub-user was deleted");
    }

    @Test
    void aTenantAdminCreatesASubUserWithACustomRole() throws Exception {
        MockHttpSession admin = tenantSession();
        // Define a custom role, then create a user AS Operator (the access tier) WITH that custom role.
        mvc.perform(post("/__admin/permissions/create-role").session(admin).with(csrf()).param("name", "BLOGGER"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__admin/users/create").session(admin).with(csrf())
                        .param("username", "blogger").param("password", "Blogger-Pw-9")
                        .param("tier", "OPERATOR").param("roles", "BLOGGER"))
                .andExpect(status().is3xxRedirection());

        User created = siteUsers.findByUsername("acme", "blogger").orElseThrow();
        assertTrue(created.roles().contains(Roles.OPERATOR), "the access tier is in the base record");
        assertFalse(created.roles().contains("BLOGGER"), "the custom role is NOT baked into the base record");
        assertTrue(grants.siteGrants("acme").rolesOf(created.principalId()).contains("BLOGGER"),
                "the custom role went to the grant file — where the matrix and a later revoke expect it");
    }

    @Test
    void creatingWithAnUnknownCustomRoleRejectsTheWholeCreate() throws Exception {
        MockHttpSession admin = tenantSession();
        mvc.perform(post("/__admin/users/create").session(admin).with(csrf())
                        .param("username", "ghost").param("password", "Ghost-Pw-9")
                        .param("tier", "OPERATOR").param("roles", "NOPE"))
                .andExpect(status().is3xxRedirection());
        assertTrue(siteUsers.findByUsername("acme", "ghost").isEmpty(),
                "an unknown custom role is rejected before the user is created — no half-created user is left");
    }
}
