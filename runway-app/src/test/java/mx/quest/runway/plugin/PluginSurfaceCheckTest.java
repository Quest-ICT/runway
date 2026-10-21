package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** The plugin surface rules — bean-name prefix and the Spring surface — before refresh (reflection) and after (real types). */
class PluginSurfaceCheckTest {

    @TempDir
    Path root;

    private static final String GOOD = """
            package fixtures.surface.good;
            import org.springframework.context.annotation.*;
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.*;
            @Configuration
            @Import(GoodConfig.Screens.class)
            public class GoodConfig {
                @Bean public String carouselManagerTitle() { return "t"; }
                @Bean(name = "carouselManagerOther") public Object other() { return new Object(); }
                @Controller @RequestMapping("/__admin/carousel-manager")
                public static class Screens {
                    @GetMapping public String list() { return "x"; }
                    @GetMapping("/edit") public String edit() { return "x"; }
                }
                @ControllerAdvice(basePackages = "fixtures.surface.good")
                public static class Advice { }
                @Bean public Advice carouselManagerAdvice() { return new Advice(); }
            }
            """;

    private static final String BAD = """
            package fixtures.surface.bad;
            import org.springframework.context.annotation.*;
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.beans.factory.config.BeanPostProcessor;
            @Configuration
            public class BadConfig {
                @Bean public String title() { return "unprefixed"; }
                @Bean public jakarta.servlet.Filter carouselManagerFilter() { return (rq, rs, ch) -> ch.doFilter(rq, rs); }
                @Bean public static BeanPostProcessor carouselManagerBpp() { return new BeanPostProcessor() { }; }
                @Bean public Object carouselManagerSneaky() { return (jakarta.servlet.Filter) (rq, rs, ch) -> { }; }
                @Bean public Steal carouselManagerSteal() { return new Steal(); }
                @Bean public Everywhere carouselManagerEverywhere() { return new Everywhere(); }
                @Controller public static class Steal {
                    @GetMapping("/__admin/users") public String users() { return "x"; }
                    @GetMapping("/__admin/carousel-managerx") public String lookalike() { return "x"; }
                }
                @ControllerAdvice public static class Everywhere { }
            }
            """;

    private Class<?> load(String id, String fqcn, String source) throws Exception {
        PluginFixture.build(root, id, "id: " + id + "\nversion: 1.0.0\n", Map.of(fqcn, source));
        URLClassLoader loader = new URLClassLoader(new URL[] {root.resolve(id).resolve(id + ".jar").toUri().toURL()},
                getClass().getClassLoader());
        return Class.forName(fqcn, true, loader);
    }

    @Test
    void theBeanPrefixIsTheIdInLowerCamelCase() {
        assertEquals("carouselManager", PluginSurfaceCheck.beanPrefix("carousel-manager"));
        assertEquals("runwayDemo", PluginSurfaceCheck.beanPrefix("runway-demo"));
        assertEquals("searchLucene", PluginSurfaceCheck.beanPrefix("search-lucene"));
        assertEquals("blog", PluginSurfaceCheck.beanPrefix("blog"));
        assertEquals("plugin:blog", PluginSurfaceCheck.mainBeanName("blog"));
    }

    @Test
    void aCompliantConfigurationHasNoProblems() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Class<?> config = load("good", "fixtures.surface.good.GoodConfig", GOOD);
        assertEquals(List.of(), PluginSurfaceCheck.beforeRefresh("carousel-manager", config));
    }

    @Test
    void everyRuleIsReportedBeforeRefresh() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Class<?> config = load("bad", "fixtures.surface.bad.BadConfig", BAD);
        List<String> problems = PluginSurfaceCheck.beforeRefresh("carousel-manager", config);
        String all = String.join("\n", problems);
        assertTrue(all.contains("bean name 'title' does not start with 'carouselManager'"), all);
        assertTrue(all.contains("'carouselManagerFilter' is a Filter"), all);
        assertTrue(all.contains("'carouselManagerBpp' is a BeanPostProcessor"), all);
        assertTrue(all.contains("maps '/__admin/users'"), all);
        assertTrue(all.contains("maps '/__admin/carousel-managerx'"), "a look-alike prefix is not the plugin's: " + all);
        assertTrue(all.contains("@ControllerAdvice fixtures.surface.bad.BadConfig$Everywhere is unrestricted"), all);
        assertFalse(all.contains("carouselManagerSneaky"), "declared as Object: only the after-refresh check sees it");
    }

    @Test
    void afterRefreshTheRealTypeIsCheckedSoAnObjectTypedFilterIsCaught() throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        Class<?> config = load("carousel-manager", "fixtures.surface.bad.BadConfig", BAD);
        PluginDescriptor d = PluginDescriptor.read(new ByteArrayInputStream(
                "id: carousel-manager\nversion: 1.0.0\nmainClass: x.Y\n".getBytes(StandardCharsets.UTF_8)));
        LoadedPlugin plugin = new LoadedPlugin(d, root.resolve("carousel-manager"));
        PluginOwnership ownership = PluginOwnership.of(new PluginRuntime(List.of(plugin),
                config.getClassLoader(), Map.of(), false));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(config.getClassLoader());
            context.registerBean("plugin:carousel-manager", config);
            context.registerBean("kernelBean", String.class, () -> "a kernel bean is never checked");
            context.refresh();
            Map<String, List<String>> found = PluginSurfaceCheck.afterRefresh(context.getBeanFactory(), ownership,
                    config.getClassLoader());
            String all = String.join("\n", found.getOrDefault("carousel-manager", List.of()));
            assertTrue(all.contains("'carouselManagerSneaky' is a Filter"), all);
            assertTrue(all.contains("bean name 'title'"), all);
            assertFalse(all.contains("plugin:carousel-manager"), "the host-chosen main bean name is exempt: " + all);
            assertFalse(found.containsKey("kernel"));
        }
    }
}
