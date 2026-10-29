package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The other half of the plugin bean-name rule: plugins prefix every bean with their id in lowerCamelCase, so the
 * kernel must never take a name with a first-party plugin's prefix — or a plugin following the rule could still
 * collide with it. Checked over a real ACTIVE context with no plugins installed (every bean is the kernel's).
 */
@SpringBootTest
class KernelBeanNamesTest {

    /** The first-party plugin ids (runway-plugins plus the essential plugin). */
    static final List<String> FIRST_PARTY = List.of("blog", "carousel-manager", "contact", "runway-demo",
            "discoverability", "search", "search-lucene", "lucene-provider", "protection", "database",
            "database-postgresql", "database-mariadb", "runway-demo-database");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-kernel-bean-names");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Bean-Names-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root", hash,
                "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void noKernelBeanNameStartsWithAFirstPartyPluginsPrefix() {
        String[] names = context.getBeanFactory().getBeanDefinitionNames();
        assertTrue(names.length > 100, "a real kernel context");
        List<String> clashes = new ArrayList<>();
        for (String name : names) {
            for (String id : FIRST_PARTY) {
                if (name.startsWith(PluginSurfaceCheck.beanPrefix(id))) {
                    clashes.add(name + " (prefix of plugin '" + id + "')");
                }
            }
        }
        assertEquals(List.of(), clashes);
    }
}
