package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.i18n.SiteAwareMessageSource;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The tenant Messages screen: renders the key×locale grid, saves through {@link SiteAwareMessageSource}
 * so an edited/added value resolves at once, drops removed keys, keeps the special-char round-trip through the read
 * side, writes per-locale files correctly, is site-isolated, and is CONFIGURE_SITE-gated.
 */
@SpringBootTest
class MessagesScreenTest {

    private static final String PW = "Msgs-Screen-7";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-2_3");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        // acme is the site under test; it offers en (default) + es so both columns render. globex is the isolation peer.
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.of("es")), null, SiteStatus.ACTIVE));
        sites.save(new Site("globex", "Globex", List.of("globex.localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));

        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path acmeUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(acmeUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));      // wildcard → has CONFIGURE_SITE
        users.save(acmeUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // no CONFIGURE_SITE
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        // Seed a globex bundle so the isolation test can prove it is byte-for-byte unchanged after an acme save.
        Path globexBase = layout.siteDir("globex").resolve("i18n").resolve("messages.properties");
        Files.createDirectories(globexBase.getParent());
        Files.writeString(globexBase, "brand=Globex\n", StandardCharsets.UTF_8);

        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private SiteAwareMessageSource siteMessages;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private static Path acmeBundle(String suffix) {
        return external.resolve("sites").resolve("acme").resolve("i18n")
                .resolve("messages" + suffix + ".properties");
    }

    @Test
    void theFormRenders() throws Exception {
        // Seed an existing key so the grid renders a real row (proving the nested row×locale th:each).
        Files.createDirectories(acmeBundle("").getParent());
        Files.writeString(acmeBundle(""), "hello.world=Hello\n", StandardCharsets.UTF_8);
        Files.writeString(acmeBundle("_es"), "hello.world=Hola\n", StandardCharsets.UTF_8);
        siteMessages.evictSite("acme");

        mvc.perform(get("/__admin/messages").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/messages\"")))
                .andExpect(content().string(Matchers.containsString("name=\"newKey\"")))
                // the add-row has a value input per locale (newValue_en / newValue_es) ...
                .andExpect(content().string(Matchers.containsString("name=\"newValue_en\"")))
                .andExpect(content().string(Matchers.containsString("name=\"newValue_es\"")))
                // ... and the existing key renders an editable row: the keep checkbox carries the key (so unticking
                // removes it), and a per-locale value input pre-fills each language.
                .andExpect(content().string(Matchers.containsString("value=\"hello.world\" checked")))
                .andExpect(content().string(Matchers.containsString("name=\"value_en\" value=\"Hello\"")))
                .andExpect(content().string(Matchers.containsString("name=\"value_es\" value=\"Hola\"")));
    }

    @Test
    void savingAKeyRoundTripsThroughTheMessageSource() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        // Add a new key with en + es values via the add-row.
        mvc.perform(post("/__admin/messages").session(admin).with(csrf())
                        .param("newKey", "nav.signin")
                        .param("newValue_en", "Sign in")
                        .param("newValue_es", "Entrar"))
                .andExpect(status().is3xxRedirection());

        // The write format + eviction prove out: the read side resolves the new value at once, per locale.
        siteResolver.bind(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.of("es")), null, SiteStatus.ACTIVE));
        try {
            assertEquals("Sign in", siteMessages.getMessage("nav.signin", null, Locale.ENGLISH));
            assertEquals("Entrar", siteMessages.getMessage("nav.signin", null, Locale.of("es")));
        } finally {
            siteResolver.clear();
        }
        // Files landed in the right place: default→base, es→suffixed.
        assertTrue(Files.readString(acmeBundle("")).contains("nav.signin=Sign in"));
        assertTrue(Files.readString(acmeBundle("_es")).contains("nav.signin=Entrar"));
    }

    @Test
    void specialCharacterValueRoundTripsThroughTheReader() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        String tricky = "a = b : c\t←ünïçödé中文"; // '=', ':', tab, unicode
        mvc.perform(post("/__admin/messages").session(admin).with(csrf())
                        .param("newKey", "tricky.key")
                        .param("newValue_en", tricky)
                        .param("newValue_es", ""))
                .andExpect(status().is3xxRedirection());

        siteResolver.bind(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.of("es")), null, SiteStatus.ACTIVE));
        try {
            assertEquals(tricky, siteMessages.getMessage("tricky.key", null, Locale.ENGLISH),
                    "the escaped value resolves back intact via the read side");
        } finally {
            siteResolver.clear();
        }
    }

    @Test
    void addingThenRemovingAKeyWorks() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        // Add two keys.
        mvc.perform(post("/__admin/messages").session(admin).with(csrf())
                        .param("newKey", "one").param("newValue_en", "1").param("newValue_es", "uno"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__admin/messages").session(admin).with(csrf())
                        // re-post "one" as an existing kept row, add "two"
                        .param("key", "one").param("keep", "one")
                        .param("value_en", "1").param("value_es", "uno")
                        .param("newKey", "two").param("newValue_en", "2").param("newValue_es", "dos"))
                .andExpect(status().is3xxRedirection());
        assertTrue(Files.readString(acmeBundle("")).contains("two=2"));

        // Now re-save keeping only "one" (un-kept "two" is dropped).
        mvc.perform(post("/__admin/messages").session(admin).with(csrf())
                        .param("key", "one").param("key", "two")
                        .param("keep", "one") // "two" NOT kept
                        .param("value_en", "1").param("value_en", "2")
                        .param("value_es", "uno").param("value_es", "dos"))
                .andExpect(status().is3xxRedirection());

        String base = Files.readString(acmeBundle(""));
        assertTrue(base.contains("one=1"), "kept key remains");
        assertTrue(!base.contains("two=2"), "un-kept key removed");
    }

    @Test
    void savingAcmeLeavesGlobexUntouched() throws Exception {
        String before = Files.readString(external.resolve("sites").resolve("globex").resolve("i18n")
                .resolve("messages.properties"), StandardCharsets.UTF_8);

        mvc.perform(post("/__admin/messages").session(sessionFor("admin")).with(csrf())
                        .param("newKey", "only.acme").param("newValue_en", "x").param("newValue_es", "y"))
                .andExpect(status().is3xxRedirection());

        String after = Files.readString(external.resolve("sites").resolve("globex").resolve("i18n")
                .resolve("messages.properties"), StandardCharsets.UTF_8);
        assertEquals(before, after, "an acme save never touches globex's bundle");
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSeeTheScreen() throws Exception {
        mvc.perform(get("/__admin/messages").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSave() throws Exception {
        mvc.perform(post("/__admin/messages").session(sessionFor("op")).with(csrf())
                        .param("newKey", "x").param("newValue_en", "y"))
                .andExpect(status().isForbidden());
    }

    // --- incremental save: POST /__admin/messages/apply (the JSON delta path) ----------------------------------

    private static void seedAcme(String base, String es) throws IOException {
        Files.createDirectories(acmeBundle("").getParent());
        Files.writeString(acmeBundle(""), base, StandardCharsets.UTF_8);
        Files.writeString(acmeBundle("_es"), es, StandardCharsets.UTF_8);
    }

    @Test
    void applyMergesOnlyTheChangedCellAndResolvesAtOnce() throws Exception {
        seedAcme("a=A\nb=B\n", "a=Aa\nb=Bb\n");
        siteMessages.evictSite("acme");

        // csrf().asHeader() sends the token as the X-CSRF-TOKEN HEADER — the exact mechanism the browser client
        // uses (the token rides in the header, not a form param), verified against the per-site CSRF repository.
        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin")).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[{\"key\":\"a\",\"locale\":\"en\",\"value\":\"A-edited\"}],\"removedKeys\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(1));

        // The edited cell is live at once AND the untouched key survived (merge, not full-grid replace).
        siteResolver.bind(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.of("es")), null, SiteStatus.ACTIVE));
        try {
            assertEquals("A-edited", siteMessages.getMessage("a", null, Locale.ENGLISH), "edited cell live");
            assertEquals("B", siteMessages.getMessage("b", null, Locale.ENGLISH), "untouched key preserved");
            assertEquals("Bb", siteMessages.getMessage("b", null, Locale.of("es")));
        } finally {
            siteResolver.clear();
        }
    }

    @Test
    void applyBlankValueClearsOnlyThatLocale() throws Exception {
        seedAcme("g=Hi\n", "g=Hola\n");
        siteMessages.evictSite("acme");

        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[{\"key\":\"g\",\"locale\":\"es\",\"value\":\"\"}],\"removedKeys\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(1));

        assertTrue(Files.readString(acmeBundle("")).contains("g=Hi"), "en keeps the key");
        assertTrue(!Files.exists(acmeBundle("_es")), "the cleared es bundle is deleted");
    }

    @Test
    void applyRemovedKeyDropsItFromEveryLocale() throws Exception {
        seedAcme("keep=K\ndrop=D\n", "keep=Kk\ndrop=Dd\n");
        siteMessages.evictSite("acme");

        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[],\"removedKeys\":[\"drop\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(1));

        assertTrue(!Files.readString(acmeBundle("")).contains("drop="), "drop gone from en");
        assertTrue(!Files.readString(acmeBundle("_es")).contains("drop="), "drop gone from es");
        assertTrue(Files.readString(acmeBundle("")).contains("keep=K"), "kept key remains");
    }

    @Test
    void applyRejectsALocaleTheSiteDoesNotOfferWith400() throws Exception {
        // 'de' is not one of acme's locales (en, es) → fail closed, never coin a messages_de bundle.
        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[{\"key\":\"x\",\"locale\":\"de\",\"value\":\"German\"}],\"removedKeys\":[]}"))
                .andExpect(status().isBadRequest());
        assertTrue(!Files.exists(acmeBundle("_de")), "no bundle created for the rejected locale");
    }

    @Test
    void applyWithNoChangesSavesZeroAndWritesNothing() throws Exception {
        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[],\"removedKeys\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(0));
    }

    @Test
    void applyIsSiteIsolatedFromGlobex() throws Exception {
        String before = Files.readString(external.resolve("sites").resolve("globex").resolve("i18n")
                .resolve("messages.properties"), StandardCharsets.UTF_8);

        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[{\"key\":\"only.acme\",\"locale\":\"en\",\"value\":\"x\"}],\"removedKeys\":[]}"))
                .andExpect(status().isOk());

        String after = Files.readString(external.resolve("sites").resolve("globex").resolve("i18n")
                .resolve("messages.properties"), StandardCharsets.UTF_8);
        assertEquals(before, after, "an acme incremental save never touches globex's bundle");
    }

    @Test
    void applyRequiresCsrf() throws Exception {
        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin"))   // no .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[{\"key\":\"x\",\"locale\":\"en\",\"value\":\"y\"}],\"removedKeys\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotApply() throws Exception {
        mvc.perform(post("/__admin/messages/apply").session(sessionFor("op")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[{\"key\":\"x\",\"locale\":\"en\",\"value\":\"y\"}],\"removedKeys\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void applyRemovalWinsOverAnUpsertOfTheSameKey() throws Exception {
        seedAcme("keep=K\nx=X\n", "keep=Kk\nx=Xx\n"); // a neighbor key so the file survives the removal
        siteMessages.evictSite("acme");

        // The client never filters a removed key out of the cells it posts, so the SERVER is the only guarantee
        // that "delete this key" beats "set its value". Post both for the same key → the key must be gone.
        mvc.perform(post("/__admin/messages/apply").session(sessionFor("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[{\"key\":\"x\",\"locale\":\"en\",\"value\":\"X-new\"}],\"removedKeys\":[\"x\"]}"))
                .andExpect(status().isOk());

        assertTrue(Files.readString(acmeBundle("")).contains("keep=K"), "the neighbor key is preserved");
        assertTrue(!Files.readString(acmeBundle("")).contains("x="), "removal wins: key gone from en despite the upsert");
        assertTrue(!Files.readString(acmeBundle("_es")).contains("x="), "and gone from es");
    }

    @Test
    void fullGridReAddingAnExistingKeyMergesRatherThanDroppingUntouchedLocales() throws Exception {
        seedAcme("greeting=Hello\n", "greeting=Hola\n");
        siteMessages.evictSite("acme");

        // The no-JS fallback: re-post the existing key as a kept row AND type it into the "Add a message" card with
        // only en filled (es blank). The merge fix must PRESERVE es=Hola (not drop it as the old replace did), so
        // the fallback agrees with the incremental path.
        mvc.perform(post("/__admin/messages").session(sessionFor("admin")).with(csrf())
                        .param("key", "greeting").param("keep", "greeting")
                        .param("value_en", "Hello").param("value_es", "Hola")
                        .param("newKey", "greeting").param("newValue_en", "Hi").param("newValue_es", ""))
                .andExpect(status().is3xxRedirection());

        assertTrue(Files.readString(acmeBundle("")).contains("greeting=Hi"), "the add-card en value is applied");
        assertTrue(Files.readString(acmeBundle("_es")).contains("greeting=Hola"),
                "the untouched es locale is preserved (merge, not destructive replace)");
    }
}
