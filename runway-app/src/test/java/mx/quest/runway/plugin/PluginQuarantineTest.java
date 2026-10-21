package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.RunwayApplication;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.BeanDefinitionOverrideException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The wiring quarantine: markers, attribution of a startup failure to one plugin, and the full loop — a
 * plugin whose bean name collides with a kernel bean breaks the boot, is quarantined (the boot ends with the relaunch
 * code), and the next boot comes up without it, Blocked with the recorded reason; Retry clears it.
 */
class PluginQuarantineTest {

    @TempDir
    Path root;

    @AfterEach
    void forget() {
        PluginQuarantine.resetForTests();
    }

    private LoadedPlugin loaded(String id, String version, Path dir) {
        PluginDescriptor d = PluginDescriptor.read(new ByteArrayInputStream(
                ("id: " + id + "\nversion: " + version + "\nmainClass: x.Y\n").getBytes(StandardCharsets.UTF_8)));
        return new LoadedPlugin(d, dir);
    }

    // ---- markers ------------------------------------------------------------------------------------------

    @Test
    void aMarkerBlocksTheSameBuildAndIsClearedWhenADifferentBuildIsInstalled() throws IOException {
        Path external = root.resolve("external");
        Path dir = Files.createDirectories(root.resolve("plugins/bad"));
        Files.write(dir.resolve("bad.jar"), new byte[] {1, 2, 3});
        LoadedPlugin bad = loaded("bad", "1.0.0", dir);

        PluginQuarantine.quarantine(external, bad, "BeanDefinitionOverrideException: bean 'siteStore'");
        assertTrue(Files.isRegularFile(external.resolve("plugin-quarantine/bad.yml")));
        assertEquals(java.util.OptionalInt.of(42), PluginQuarantine.exitCodeAfterFailure());

        Map<String, String> blocked = PluginQuarantine.blocked(external, List.of(bad));
        assertTrue(blocked.get("bad").startsWith("quarantined:"), blocked.toString());
        assertTrue(blocked.get("bad").contains("siteStore"));

        // Same version, different jar bytes: a rebuilt plugin gets a fresh chance; the read-only preview keeps the file.
        Files.write(dir.resolve("bad.jar"), new byte[] {9});
        assertTrue(PluginQuarantine.blocked(external, List.of(bad), false).isEmpty());
        assertTrue(Files.exists(external.resolve("plugin-quarantine/bad.yml")), "a preview never changes the disk");
        assertTrue(PluginQuarantine.blocked(external, List.of(bad)).isEmpty());
        assertFalse(Files.exists(external.resolve("plugin-quarantine/bad.yml")), "the boot clears a stale marker");

        // A different version: likewise.
        Files.write(dir.resolve("bad.jar"), new byte[] {1, 2, 3});
        PluginQuarantine.quarantine(external, bad, "x");
        assertTrue(PluginQuarantine.blocked(external, List.of(loaded("bad", "1.0.1", dir))).isEmpty());
    }

    @Test
    void clearRemovesTheMarkerAndUnsafeIdsAreIgnored() throws IOException {
        Path external = root.resolve("external");
        LoadedPlugin p = loaded("p", "1.0.0", Files.createDirectories(root.resolve("plugins/p")));
        PluginQuarantine.quarantine(external, p, "boom");
        assertTrue(PluginQuarantine.clear(external, "p"));
        assertFalse(PluginQuarantine.clear(external, "p"), "already gone");
        assertFalse(PluginQuarantine.clear(external, "../escape"));
        assertTrue(PluginQuarantine.read(external, "../escape").isEmpty());
    }

    @Test
    void anOrdinaryFailureHasNoRelaunchCode() {
        assertTrue(PluginQuarantine.exitCodeAfterFailure().isEmpty());
    }

    // ---- attribution --------------------------------------------------------------------------------------

    private record Fixture(PluginOwnership ownership, ClassLoader loader, LoadedPlugin plugin, LoadedPlugin essential) {
    }

    private Fixture fixture() throws Exception {
        Path plugins = root.resolve("plugins");
        PluginFixture.build(plugins, "p1", "id: p1\nversion: 1.0.0\n", Map.of("fixtures.q.p1.Thing",
                "package fixtures.q.p1; public class Thing { public Thing() { } }"));
        PluginFixture.build(plugins, "p2", "id: p2\nversion: 1.0.0\n", Map.of("fixtures.q.p2.Other",
                "package fixtures.q.p2; public class Other { }"));
        PluginFixture.build(plugins, "protection", "id: protection\nversion: 1.0.0\n", Map.of(
                "fixtures.q.prot.Guard", "package fixtures.q.prot; public class Guard { }"));
        URLClassLoader loader = new URLClassLoader(new URL[] {plugins.resolve("p1/p1.jar").toUri().toURL(),
                plugins.resolve("p2/p2.jar").toUri().toURL(),
                plugins.resolve("protection/protection.jar").toUri().toURL()}, getClass().getClassLoader());
        LoadedPlugin p1 = loaded("p1", "1.0.0", plugins.resolve("p1"));
        LoadedPlugin p2 = loaded("p2", "1.0.0", plugins.resolve("p2"));
        LoadedPlugin protection = loaded("protection", "1.0.0", plugins.resolve("protection"));
        return new Fixture(PluginOwnership.of(new PluginRuntime(List.of(p1, p2, protection), loader, Map.of(),
                false)), loader, p1, protection);
    }

    @Test
    void aBeanCreationFailureIsPinnedOnThePluginWhoseJarDeclaresTheBean() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Fixture f = fixture();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerBeanDefinition("p1Thing", new RootBeanDefinition("fixtures.q.p1.Thing"));
        beans.registerBeanDefinition("kernelThing", new RootBeanDefinition(String.class));
        beans.registerBeanDefinition("guard", new RootBeanDefinition("fixtures.q.prot.Guard"));

        RuntimeException wrapped = new BeanCreationException("kernelThing", "needs p1Thing",
                new BeanCreationException("p1Thing", "constructor threw", new IllegalStateException("boom")));
        assertEquals("p1", PluginQuarantine.attribute(wrapped, beans, f.ownership(), f.loader()).orElseThrow().id(),
                "the innermost attributable bean is the culprit, not the kernel bean that needed it");
        beans.registerBeanDefinition("p2Other", new RootBeanDefinition("fixtures.q.p2.Other"));
        RuntimeException crossPlugin = new BeanCreationException("p2Other", "needs p1Thing",
                new BeanCreationException("p1Thing", "constructor threw", new IllegalStateException("boom")));
        assertEquals("p1", PluginQuarantine.attribute(crossPlugin, beans, f.ownership(), f.loader()).orElseThrow()
                .id(), "p2 only failed because p1's bean did: p1 is quarantined, not p2");
        assertTrue(PluginQuarantine.attribute(new BeanCreationException("kernelThing", "x"), beans, f.ownership(),
                f.loader()).isEmpty(), "a kernel failure stays an ordinary failure");
        assertTrue(PluginQuarantine.attribute(new BeanCreationException("guard", "x"), beans, f.ownership(),
                f.loader()).isEmpty(), "the essential plugin is never quarantined");
        assertTrue(PluginQuarantine.attribute(new IllegalStateException("no bean"), beans, f.ownership(),
                f.loader()).isEmpty());
    }

    @Test
    void aBeanNameClashIsPinnedOnThePluginSideWhicheverSideItIs() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Fixture f = fixture();
        RootBeanDefinition kernel = new RootBeanDefinition(String.class);
        RootBeanDefinition plugin = new RootBeanDefinition("fixtures.q.p1.Thing");
        assertEquals("p1", PluginQuarantine.attribute(new BeanDefinitionOverrideException("siteStore", plugin, kernel),
                null, f.ownership(), f.loader()).orElseThrow().id());
        assertEquals("p1", PluginQuarantine.attribute(new BeanDefinitionOverrideException("siteStore", kernel, plugin),
                null, f.ownership(), f.loader()).orElseThrow().id());
    }

    // ---- the full loop over two real boots ----------------------------------------------------------------

    /**
     * A plugin that passes every load-time rule (SPI-only, prefixed bean names) and still breaks the refresh: its bean
     * throws while being created. (Before SPI 1.0 the fixture clashed with a kernel bean name; the before-refresh
     * name rule now blocks that before wiring, so it no longer reaches the quarantine.)
     */
    private static final String CLASH_CONFIG = """
            package fixtures.q.clash;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class ClashConfig {
                @Bean public String clashThing() { throw new IllegalStateException("clash could not start"); }
            }
            """;

    /**
     * Passes the before-refresh check (its {@code @Bean}s are fine) but component-scans a servlet {@code Filter} with an
     * unprefixed name — only the after-refresh check sees it.
     */
    private static final String SCAN_CONFIG = """
            package fixtures.q.scan;
            import org.springframework.context.annotation.ComponentScan;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            @ComponentScan("fixtures.q.scan.stray")
            public class ScanConfig {
            }
            """;

    private static final String STRAY_FILTER = """
            package fixtures.q.scan.stray;
            import jakarta.servlet.FilterChain;
            import jakarta.servlet.ServletRequest;
            import jakarta.servlet.ServletResponse;
            import org.springframework.stereotype.Component;
            @Component
            public class StrayFilter implements jakarta.servlet.Filter {
                public void doFilter(ServletRequest q, ServletResponse r, FilterChain c)
                        throws java.io.IOException, jakarta.servlet.ServletException {
                    c.doFilter(q, r);
                }
            }
            """;

    private ConfigurableApplicationContext boot(Path external) {
        return new SpringApplicationBuilder(RunwayApplication.class).properties(
                "server.port=0", "spring.main.banner-mode=off",
                "runway.storage.external=" + external,
                "runway.security.app-master-id=app-master").run();
    }

    @Test
    void aPluginThatBreaksTheBootIsQuarantinedAndTheNextBootComesUpWithoutIt() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Path external = Files.createDirectories(root.resolve("e2e"));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Quarantine-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root", hash,
                "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        PluginFixture.build(external.resolve("plugins"), "clash",
                "id: clash\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: fixtures.q.clash.ClashConfig\n",
                Map.of("fixtures.q.clash.ClashConfig", CLASH_CONFIG));

        assertThrows(Exception.class, () -> boot(external), "the first boot fails on the throwing bean");
        assertTrue(Files.isRegularFile(external.resolve("plugin-quarantine/clash.yml")), "the marker was written");
        assertEquals(java.util.OptionalInt.of(42), PluginQuarantine.exitCodeAfterFailure(),
                "main exits with the supervisor's relaunch code");
        PluginQuarantine.resetForTests();

        try (ConfigurableApplicationContext second = boot(external)) {
            PluginRuntime runtime = second.getBean(PluginRuntime.class);
            assertTrue(runtime.loaded().stream().noneMatch(p -> p.id().equals("clash")));
            assertTrue(runtime.blocked().get("clash").startsWith("quarantined:"), runtime.blocked().toString());
            assertTrue(PluginQuarantine.exitCodeAfterFailure().isEmpty());
            // Retry from the plugins screen clears the marker for the boot after this one.
            PluginAdminService admin = second.getBean(PluginAdminService.class);
            PluginInstallation row = admin.list().stream().filter(r -> r.id().equals("clash")).findFirst()
                    .orElseThrow();
            assertTrue(row.quarantined());
            assertEquals(PluginStatus.BLOCKED, row.status());
            assertTrue(admin.retry("clash"));
            assertFalse(Files.exists(external.resolve("plugin-quarantine/clash.yml")));
        }
    }

    /**
     * SPI 1.0: what the before-refresh check cannot see — a component-scanned servlet {@code Filter} with an unprefixed
     * name — the after-refresh check finds; it cannot unload a live bean, so it quarantines the plugin and fails the
     * boot with the relaunch code, and the next boot comes up with the plugin Blocked and the rule it broke as the
     * reason.
     */
    @Test
    void anAfterRefreshSurfaceViolationQuarantinesThePluginAndTheNextBootBlocksIt() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Path external = Files.createDirectories(root.resolve("scan-e2e"));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Quarantine-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root", hash,
                "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        PluginFixture.build(external.resolve("plugins"), "scan",
                "id: scan\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: fixtures.q.scan.ScanConfig\n",
                Map.of("fixtures.q.scan.ScanConfig", SCAN_CONFIG, "fixtures.q.scan.stray.StrayFilter", STRAY_FILTER));

        Exception failure = assertThrows(Exception.class, () -> boot(external));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof PluginCompliance.SurfaceViolationException, cause.toString());
        PluginQuarantine.Marker marker = PluginQuarantine.read(external, "scan").orElseThrow();
        assertTrue(marker.reason().contains("bean 'strayFilter' is a Filter, which a plugin may not contribute"),
                marker.reason());
        assertTrue(marker.reason().contains("bean name 'strayFilter' does not start with 'scan'"), marker.reason());
        assertEquals(java.util.OptionalInt.of(42), PluginQuarantine.exitCodeAfterFailure(),
                "main exits with the supervisor's relaunch code");
        PluginQuarantine.resetForTests();

        try (ConfigurableApplicationContext second = boot(external)) {
            PluginRuntime runtime = second.getBean(PluginRuntime.class);
            assertTrue(runtime.loaded().stream().noneMatch(p -> p.id().equals("scan")));
            assertTrue(runtime.blocked().get("scan").startsWith("quarantined:"), runtime.blocked().toString());
            assertTrue(runtime.blocked().get("scan").contains("is a Filter"), runtime.blocked().toString());
            assertTrue(PluginQuarantine.exitCodeAfterFailure().isEmpty());
        }
    }
}
