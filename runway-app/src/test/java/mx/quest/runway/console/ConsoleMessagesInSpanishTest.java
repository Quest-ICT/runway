package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.log.SiteLogConfig;
import mx.quest.runway.log.SiteLogConfigService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
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
 * The console's newer messages come back in the operator's language: the Account page's messages, the
 * Server settings save confirmation and its log-ceiling note, and the secret fields' placeholders, here with the
 * console set to Spanish by its language cookie. Before, these were English literals in the code.
 */
@SpringBootTest
class ConsoleMessagesInSpanishTest {

    private static final String PW = "Mensajes-En-Espanol-9";
    private static final Cookie SPANISH = new Cookie("runway-op-lang", "es");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-messages-es");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x.test", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-es", "opes", hash, "Op", "op@old.test", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.security.secret-key", () -> "messages-es-test-master-key-0123456789abcdef");
    }

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void theAccountPageAnswersInSpanish() throws Exception {
        MockHttpSession session = login("opes");
        mvc.perform(post("/__app/account/profile").session(session).cookie(SPANISH).with(csrf())
                        .param("displayName", "Olga").param("recoveryEmail", "op@new.test"))
                .andExpect(flash().attribute("error",
                        "Escriba su contraseña actual para cambiar su correo de recuperación."));
        mvc.perform(post("/__app/account/profile").session(session).cookie(SPANISH).with(csrf())
                        .param("displayName", "Olga").param("recoveryEmail", "not an address")
                        .param("currentPassword", PW))
                .andExpect(flash().attribute("error", "Esa no es una dirección de correo válida."));
        mvc.perform(post("/__app/account/profile").session(session).cookie(SPANISH).with(csrf())
                        .param("displayName", "Olga").param("recoveryEmail", "op@new.test")
                        .param("currentPassword", "wrong"))
                .andExpect(flash().attribute("error", "La contraseña actual no es correcta."));
        mvc.perform(post("/__app/account/profile").session(session).cookie(SPANISH).with(csrf())
                        .param("displayName", "Olga").param("recoveryEmail", "op@new.test")
                        .param("currentPassword", PW))
                .andExpect(flash().attribute("flash", "Su perfil se guardó."));
        mvc.perform(post("/__app/account").session(session).cookie(SPANISH).with(csrf())
                        .param("currentPassword", PW).param("newPassword", "Uno-Dos-Tres-44")
                        .param("confirmPassword", "Otro-55"))
                .andExpect(flash().attribute("error", "La nueva contraseña y su confirmación no coinciden."));
    }

    @Test
    void serverSettingsAnswerInSpanish(@Autowired SiteStore sites, @Autowired SiteLogConfigService siteLogs)
            throws Exception {
        sites.save(new Site("longlog", "L", List.of("longlog.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        siteLogs.save("longlog", new SiteLogConfig(true, 60));
        MockHttpSession admin = login("admin");
        mvc.perform(post("/__app/config/server").session(admin).cookie(SPANISH).with(csrf())
                        .param("runway.logs.max-retention-days", "40"))
                .andExpect(flash().attribute("flash", Matchers.allOf(
                        Matchers.startsWith("Guardado. Los cambios se aplican tras el próximo reinicio."),
                        Matchers.containsString("1 sitio conserva registros por más de 40 días"))));

        String page = mvc.perform(get("/__app/config/server").session(admin).cookie(SPANISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("placeholder=\"sin definir\""), "a secret field's placeholder is in Spanish");
        assertFalse(page.contains("placeholder=\"not set\""));
    }
}
