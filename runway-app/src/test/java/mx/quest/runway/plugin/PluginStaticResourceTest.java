package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A loaded plugin's bundled static assets are served from its own namespace, {@code /__plugin/<id>/static/**}. */
@SpringBootTest
class PluginStaticResourceTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-8g-static");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.buildAlpha(external.resolve("plugins")); // ships static/hello.txt
        }
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Sup3r-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aBundledAssetIsServedFromThePluginNamespace() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        mvc.perform(get("/__plugin/alpha/static/hello.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(PluginFixture.ALPHA_STATIC_BODY));
    }

    @Test
    void aMissingAssetIsNotFound() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        mvc.perform(get("/__plugin/alpha/static/nope.txt"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("max-age=3600"))));
    }

    /**
     * A plugin file is cacheable (an hour by default) with a content ETag and no Last-Modified, instead of
     * Spring Security's no-store, which would make every page view download it again.
     */
    @Test
    void aBundledAssetIsCacheableAndRevalidatesByItsContentEtag() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        var first = mvc.perform(get("/__plugin/alpha/static/hello.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(header().doesNotExist("Last-Modified"))
                .andExpect(header().exists("ETag"))
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get("/__plugin/alpha/static/hello.txt").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/__plugin/alpha/static/hello.txt").header("If-None-Match", "\"other\""))
                .andExpect(status().isOk());
        mvc.perform(get("/__plugin/alpha/static/hello.txt").header("If-Modified-Since", "Sat, 01 Jan 2050 00:00:00 GMT"))
                .andExpect(status().isOk());
    }
}
