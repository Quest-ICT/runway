package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
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
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The anonymous-route contract end to end, through the <b>real</b> tenant security chain and dispatcher (Q-A, SPI
 * 0.5.0): a form POST to an anonymous route needs no CSRF token and runs with no principal (so {@code AccessCheck}
 * says no even for a signed-in administrator); every other POST — an ordinary plugin route, a console form — still
 * needs its token. Also that {@link AccessCheck} is the {@code authz} bean.
 */
@SpringBootTest
@Import(AnonymousRouteSecurityTest.Routes.class)
class AnonymousRouteSecurityTest {

    private static final String PW = "Anon-Route-Pw-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-k2-anon");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("acme-admin", "admin", hash, "Admin", "",
                true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** A kernel-side contributor standing in for a plugin's: one anonymous form route, one ordinary POST route. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Routes {
        @Bean
        EndpointContributor k2AnonymousProbe(AccessCheck access) {
            return new EndpointContributor() {
                @Override
                public String key() {
                    return "k2-probe";
                }

                @Override
                public List<Route> routes() {
                    return List.of(Route.anonymousPost("/k2/anon", null),
                            new Route("POST", "/k2/ordinary", Realm.TENANT, null));
                }

                @Override
                public RouteResponse handle(RouteRequest request) {
                    return RouteResponse.text(200, "may=" + access.may("EDIT_PAGES")
                            + " name=" + request.param("name").orElse("-"));
                }
            };
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        FilterRegistrationBean<EndpointDispatchFilter> dispatch =
                context.getBean("endpointDispatchFilterRegistration", FilterRegistrationBean.class);
        // The production order: site binding, then the security chain, then endpoint dispatch.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver),
                        context.getBean("springSecurityFilterChain", Filter.class), dispatch.getFilter())
                .build();
    }

    private MockHttpSession adminSession() throws Exception {
        return (MockHttpSession) mvc.perform(formLogin("/login").user("admin").password(PW)).andReturn()
                .getRequest().getSession(false);
    }

    @Test
    void anAnonymousFormRouteNeedsNoCsrfToken() throws Exception {
        mvc.perform(post("/k2/anon").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("name=Ana"))
                .andExpect(status().isOk())
                .andExpect(content().string("may=false name=Ana"));
    }

    @Test
    void aSignedInAdministratorIsStillNobodyInsideAnAnonymousRoute() throws Exception {
        MockHttpSession admin = adminSession();
        mvc.perform(post("/k2/ordinary").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("name=x"))
                .andExpect(status().isOk())
                .andExpect(content().string("may=true name=x")); // the same principal on an ordinary route
        mvc.perform(post("/k2/anon").session(admin)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("name=x"))
                .andExpect(status().isOk())
                .andExpect(content().string("may=false name=x"));
    }

    @Test
    void anOrdinaryPluginPostStillNeedsItsToken() throws Exception {
        mvc.perform(post("/k2/ordinary").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("name=x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void consoleFormsAndTheLoginFormStillNeedTheirToken() throws Exception {
        MockHttpSession admin = adminSession();
        mvc.perform(post("/__admin/pages/home/disable").session(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/login").param("username", "admin").param("password", PW))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousRouteRefusesANonFormBody() throws Exception {
        mvc.perform(post("/k2/anon").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void accessCheckIsTheAuthzBean() {
        assertSame(context.getBean(AccessCheck.BEAN_NAME), context.getBean(AccessCheck.class));
    }
}
