package mx.quest.runway.console;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/**
 * The tenant page-editor live preview must resolve the SITE's {@code #{}} strings, even though it is served
 * from a {@code /__admin} console URL. Regression for the bug where a page's own {@code #{}} keys echoed as
 * bare codes in the preview because the console-path routing handed them the operator string namespace.
 */
@SpringBootTest
class PagePreviewI18nTest {

    private static final String PW = "Preview-I18n-7";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-prev-i18n");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));

        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        // A per-site i18n bundle with one key.
        Path i18n = layout.siteDir("acme").resolve("i18n");
        Files.createDirectories(i18n);
        write(i18n.resolve("messages.properties"), "chrome.brand=BRAND_RESOLVED\n");

        // Master binds #brand via a #{} MESSAGE (not a model var); home pulls the master.
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html><html><head><title>Master</title></head>
                <body><header id="site-header"><span id="brand">PH</span></header>
                <main id="content">m</main><footer id="site-footer">Foot</footer></body></html>""");
        write(templates.resolve("_master.th.xml"), """
                <?xml version="1.0"?>
                <thlogic><attr sel="#brand" th:text="#{chrome.brand}"/></thlogic>""");
        write(templates.resolve("home.html"), """
                <!DOCTYPE html><html><head><title>Home</title></head>
                <body><header id="site-header">PH</header>
                <main id="content"><h1 id="hometitle">PH</h1></main>
                <footer id="site-footer">PH</footer></body></html>""");
        write(templates.resolve("home.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#site-footer" th:replace="~{${master} :: #site-footer}"/>
                  <attr sel="#hometitle" th:text="${pageTitle}"/>
                </thlogic>""");
        write(templates.resolve("home.meta.yml"), "path: /\ntemplate: _master\ndraft: true\ntitle:\n  en: Home Page\n");

        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void previewResolvesAMessageInTheMaster() throws Exception {
        mvc.perform(get("/__admin/pages/home/preview").session(sessionFor("admin")))
                .andExpect(status().isOk())
                // If the site is bound at render, #{chrome.brand} -> "BRAND_RESOLVED".
                // If not, the no-site fallback echoes the code "chrome.brand" (the reported bug).
                .andExpect(content().string(Matchers.containsString("BRAND_RESOLVED")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("chrome.brand"))));
    }
}
