package mx.quest.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.Yaml;

/**
 * The plugin's contract with the host, checked at build time: the wiring over SPI services only, the bean-name rule,
 * the descriptor (SPI floor, release version, no personal data, the settings the code reads), the i18n bundles, and
 * that the compiled classes link nothing of the host but the SPI — the same things the kernel's load-time checks
 * warn about (and refuse from Runway 1.0).
 */
class BlogConfigTest {

    private static final String PREFIX = "blog";

    static Set<String> keys(String file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new TreeSet<>(p.stringPropertyNames());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> descriptor() throws IOException {
        try (Reader in = Files.newBufferedReader(Path.of("plugin.yml"), StandardCharsets.UTF_8)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    private static Set<String> beanMethodNames() {
        return Arrays.stream(BlogConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void wiresEverythingFromSpiServicesUnderPrefixedNames(@TempDir Path sites) throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        FakeSiteServices kernel = new FakeSiteServices()
                .site(new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null))
                .current("acme");
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PluginSettings.class, () -> new FakePluginSettings());
            ctx.registerBean(PluginSiteFiles.class, () -> new TempDirSiteFiles(sites, "blog"));
            ctx.registerBean(ContentCatalogView.class, () -> new FakeContentCatalog().site("acme"));
            ctx.registerBean(ContentChanges.class, () -> new EventDispatch("blog", Set.of("blog")));
            ctx.registerBean(FakeSiteServices.class, () -> kernel);
            ctx.register(BlogConfig.class);
            ctx.refresh();

            assertEquals(Set.of("blogStore", "blogContentSource", "blogAdminController"),
                    new TreeSet<>(Arrays.stream(ctx.getBeanDefinitionNames())
                            .filter(n -> n.startsWith("blog") && !n.equals("blogConfig")).toList()),
                    "the mainClass itself is registered by the host as plugin:blog");
            BlogContentSource source = ctx.getBean("blogContentSource", BlogContentSource.class);
            assertSame(source, ctx.getBean(ContentSource.class), "collected as the site's content source");
            assertSame(source, ctx.getBean(SiteEventListener.class), "…and as its own index's listener");
            assertEquals("blog", source.key());
            ctx.getBean("blogStore", BlogStore.class).save("acme", new BlogEntry("hi", Map.of("en", "Hi"), Map.of(),
                    List.of("en"), null, Map.of(), null, true, null, null, null), Map.of("en", "x"));
            assertTrue(Files.isRegularFile(sites.resolve("acme/plugins/blog/entries/hi/entry.yml")),
                    "the store writes through the injected PluginSiteFiles");
            org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();
            ctx.getBean("blogAdminController", BlogAdminController.class).list(model);
            assertEquals(1, ((List<?>) model.getAttribute("rows")).size(),
                    "the controller reads the bound site through RequestContext");
        }
    }

    @Test
    void everyBeanNameStartsWithThePluginId() {
        assertEquals(Set.of("blogStore", "blogContentSource", "blogAdminController"), beanMethodNames());
        for (Method m : BlogConfig.class.getDeclaredMethods()) {
            Bean bean = m.getAnnotation(Bean.class);
            if (bean != null) {
                for (String alias : bean.name()) {
                    assertTrue(alias.startsWith(PREFIX), "an explicit bean name must start with " + PREFIX + ": " + alias);
                }
            }
        }
    }

    @Test
    void theDescriptorDeclaresTheSpiFloorAndTheReleaseVersion() throws IOException {
        Map<String, Object> yml = descriptor();
        assertEquals("blog", yml.get("id"));
        assertEquals(BlogConfig.class.getName(), yml.get("mainClass"));
        assertEquals(">=1.0.0", yml.get("requiresSpi"), "built for the frozen SPI 1.0.0 contract");
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("<artifactId>runway-plugin-blog</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(version.find());
        assertEquals("2.1.0", version.group(1));
        assertEquals(version.group(1), String.valueOf(yml.get("version")), "plugin.yml and pom.xml agree");
        assertTrue(pom.contains("<project.build.outputTimestamp>2026-10-21T18:56:00Z</project.build.outputTimestamp>"));
        assertFalse(pom.contains("<artifactId>runway-app</artifactId>"), "no runway-app dependency in any scope");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDescriptorDeclaresNoPersonalDataAndTheSettingsTheCodeReads() throws IOException {
        Map<String, Object> yml = descriptor();
        assertNull(yml.get("siteData"), "blog entries are published content the site's operators author, not personal data");
        List<Map<String, Object>> schema = (List<Map<String, Object>>) yml.get("siteSettings");
        Map<String, Object> byKey = new java.util.HashMap<>();
        schema.forEach(s -> byKey.put(String.valueOf(s.get("key")), s.get("default")));
        assertEquals(Map.of("enabled", true, "prefix", BlogSettings.DEFAULT_PREFIX,
                "template", BlogSettings.DEFAULT_TEMPLATE), byKey, "the code's fallbacks match the declared defaults");
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) yml.get("permissions");
        assertEquals(List.of("settings", "manage"), permissions.stream().map(p -> p.get("id")).toList());
        List<Map<String, Object>> menu = (List<Map<String, Object>>) yml.get("menu");
        assertEquals("/__admin/blog", menu.get(0).get("path"));
        assertEquals("blog|manage", menu.get(0).get("permission"));
    }

    @Test
    void theEnglishAndSpanishBundlesDefineTheSameKeysAndEveryDescriptorLabel() throws IOException {
        Set<String> en = keys("i18n/messages.properties");
        assertEquals(en, keys("i18n/messages_es.properties"));
        String yml = Files.readString(Path.of("plugin.yml"), StandardCharsets.UTF_8);
        Matcher label = Pattern.compile("label: (blog\\.[\\w.]+)").matcher(yml);
        int found = 0;
        while (label.find()) {
            found++;
            assertTrue(en.contains(label.group(1)), "descriptor label without words: " + label.group(1));
        }
        assertEquals(6, found, "2 permissions, 3 settings, 1 menu entry");
    }

    @Test
    void theCompiledClassesLinkNoHostInternals() throws IOException {
        Path classes = Path.of("target", "classes");
        Pattern host = Pattern.compile("mx[/.]quest[/.]runway[/.](?!spi[/.])[\\w/.$]*");
        List<String> problems = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                scanned++;
                String rel = classes.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("mx/quest/runway/")) {
                    problems.add(rel + " lives under mx.quest.runway");
                }
                // The constant pool holds class names and descriptors as (modified) UTF-8; Latin-1 keeps every
                // byte as one char, so an ASCII pattern finds them without parsing the class file.
                Matcher m = host.matcher(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    problems.add(rel + " → " + m.group());
                }
            }
        }
        assertTrue(scanned >= 6, "the compiled classes were found (" + scanned + ")");
        assertTrue(problems.isEmpty(), "host linkage: " + problems);
    }
}
