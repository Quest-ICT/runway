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
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.SeedTemplateStore;
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
 * The Site templates screen end to end: an operator template's page offers its name and description for editing
 * (the built-in default's does not), the save goes through the {@code MANAGE_STYLES} gate, and an operator without
 * it is refused.
 */
@SpringBootTest
class SiteTemplatesScreenTest {

    private static final String PW = "Templates-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-sitetpl");
        var enc = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
        Path appUsers = external.resolve("app-users.yml");
        UserStore users = new UserStore(new YamlStore());
        users.save(appUsers, new User("app-master", "admin", enc.encode(PW), "Admin", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(appUsers, new User("app-op", "operator", enc.encode(PW), "Operator", "", true, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SeedTemplateStore catalog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (!catalog.templateExists("corp")) {
            catalog.createTemplate("corp", "Corporate", "Blue and grey", null);
        }
    }

    private MockHttpSession login(String user) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(user).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void anOperatorTemplateOffersItsNameAndDescription() throws Exception {
        MockHttpSession admin = login("admin");
        String html = mvc.perform(get("/__app/site-templates/corp").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__app/site-templates/corp/details\""));
        assertTrue(html.contains("value=\"Blue and grey\""), "the description is prefilled");
        String builtIn = mvc.perform(get("/__app/site-templates/default").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(builtIn.contains("/details\""), "the built-in default keeps its localized name");
    }

    @Test
    void theSaveIsGatedLikeTheRestOfTheScreen() throws Exception {
        mvc.perform(post("/__app/site-templates/corp/details").session(login("operator")).with(csrf())
                        .param("name", "Hijacked"))
                .andExpect(status().isForbidden());
        assertEquals("Corporate", catalog.template("corp").name());
        mvc.perform(post("/__app/site-templates/corp/details").session(login("admin")).with(csrf())
                        .param("name", "Corporate").param("description", "Blue and grey"))
                .andExpect(status().is3xxRedirection());
    }
}
