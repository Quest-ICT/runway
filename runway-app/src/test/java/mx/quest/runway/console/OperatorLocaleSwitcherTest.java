package mx.quest.runway.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
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
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.hamcrest.Matchers;
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
 * The operator-console language switcher end-to-end: it renders in the topbar, the {@code /__ui-lang} endpoint
 * remembers the choice and guards its redirect, and a remembered Spanish choice actually renders the console in
 * Spanish. Also asserts the shell styling (full-width {@code layout-fluid}, dark topbar, plain realm
 * legend, 3rem menu gap).
 */
@SpringBootTest
class OperatorLocaleSwitcherTest {

    private static final String PW = "Op-Locale-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-op-locale");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(new SiteContextFilter(siteResolver))
                    .apply(springSecurity()).build();
        }
        return mvc;
    }

    private MockHttpSession login() throws Exception {
        MvcResult r = mvc().perform(formLogin("/app/login").user("root").password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void switcherAndRestyleRenderInTheTopbar() throws Exception {
        MockHttpSession session = login();
        String html = mvc().perform(get("/__app").session(session).locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // The language switcher, before the user menu, with a GET item per offered language and the current label.
        assertThat(html).contains("id=\"tb-langswitch\"").contains("fa-language");
        assertThat(html).contains("/__ui-lang?lang=en").contains("/__ui-lang?lang=es");
        assertThat(html).contains("English").contains("Español");
        // The shell styling.
        assertThat(html).contains("layout-fluid");                 // full-width body
        assertThat(html).contains("rw-topbar-app").contains("data-bs-theme=\"dark\""); // dark app bar
        assertThat(html).contains("rw-realm-legend");              // plain legend (not a badge pill)
        assertThat(html).contains("rw-menu-gap");                  // 3rem gap to the first menu item
    }

    @Test
    void switchingRemembersTheChoiceAndRedirectsBack() throws Exception {
        MvcResult r = mvc().perform(get("/__ui-lang").param("lang", "es").param("to", "/__app"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/__app")).andReturn();
        Cookie set = r.getResponse().getCookie(OperatorLocaleResolver.COOKIE);
        assertThat(set).isNotNull();
        assertThat(set.getValue()).isEqualTo("es");
    }

    @Test
    void aRememberedSpanishChoiceRendersTheConsoleInSpanish() throws Exception {
        MockHttpSession session = login();
        String html = mvc().perform(get("/__app").session(session)
                        .cookie(new Cookie(OperatorLocaleResolver.COOKIE, "es")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // The ES operator bundle: the realm legend + a menu label resolve in Spanish.
        assertThat(html).contains("Gestión de la aplicación Runway"); // op.realm.application (es)
        assertThat(html).contains("Configuración");                    // op.nav.settings (es)
    }

    @Test
    void theRedirectTargetIsGuardedAgainstOpenRedirect() throws Exception {
        mvc().perform(get("/__ui-lang").param("lang", "es").param("to", "https://evil.example/x"))
                .andExpect(redirectedUrl("/__app"));
        mvc().perform(get("/__ui-lang").param("lang", "es").param("to", "//evil.example"))
                .andExpect(redirectedUrl("/__app"));
    }

    @Test
    void anUnknownLanguageIsIgnored() throws Exception {
        MvcResult r = mvc().perform(get("/__ui-lang").param("lang", "zz").param("to", "/__app"))
                .andExpect(redirectedUrl("/__app")).andReturn();
        // No cookie is written for a language that isn't offered.
        assertThat(r.getResponse().getCookie(OperatorLocaleResolver.COOKIE)).isNull();
    }
}
