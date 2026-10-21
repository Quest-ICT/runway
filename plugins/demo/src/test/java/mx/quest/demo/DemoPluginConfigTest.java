package mx.quest.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
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
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.PluginLifecycle;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * The plugin's contract with the host, checked at build time — the same things the kernel's load-time checks warn
 * about (and refuse from Runway 1.0): wiring over SPI services only, the bean-name rule, the allowed Spring surface,
 * the console controller's mappings and gates, the bean names the templates reference, the descriptor (SPI floor,
 * release version, personal data), the i18n bundles, and that the compiled classes link nothing of the host but the
 * SPI.
 */
class DemoPluginConfigTest {

    private static final String PREFIX = "runwayDemo";

    private static Set<String> beanMethodNames() {
        return Arrays.stream(DemoPluginConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> descriptor() throws IOException {
        try (Reader in = Files.newBufferedReader(Path.of("plugin.yml"), StandardCharsets.UTF_8)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @Test
    void wiresEverythingFromSpiServicesAlone(@TempDir Path sites) throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        FakeSiteServices kernel = new FakeSiteServices()
                .site(new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null))
                .page("acme", DemoConsoleController.STARTER_SLUG, "/demo-moved");
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PluginSettings.class, () -> new FakePluginSettings()
                    .secret("acme", "analytics.site-token", "t0k3n"));
            ctx.registerBean(PluginSiteFiles.class, () -> new TempDirSiteFiles(sites, "runway-demo"));
            ctx.registerBean(ContentChanges.class, () -> new EventDispatch("runway-demo", Set.of("runway-demo")));
            ctx.registerBean(PluginInfo.class, () -> new PluginInfo("runway-demo", "2.0.1", sites));
            ctx.registerBean(FakeSiteServices.class, () -> kernel);
            ctx.registerBean(FakeContentCatalog.class, () -> new FakeContentCatalog().site("acme"));
            ctx.register(DemoPluginConfig.class);
            ctx.refresh();

            assertEquals(Set.of("runwayDemoAnalyticsHead"), ctx.getBeansOfType(HeadContributor.class).keySet());
            assertEquals(Set.of("runwayDemoNoticeWidget", "runwayDemoFeedbackWidget"),
                    ctx.getBeansOfType(ContentWidget.class).keySet());
            assertEquals(Set.of("runwayDemoMetaLengthCheck"), ctx.getBeansOfType(PageQualityCheck.class).keySet());
            assertEquals(Set.of("runwayDemoEndpoints"), ctx.getBeansOfType(EndpointContributor.class).keySet());
            assertEquals(Set.of("runwayDemoShowcaseSource"), ctx.getBeansOfType(ContentSource.class).keySet());
            assertEquals(Set.of("runwayDemoShowcaseSource", "runwayDemoActivity"),
                    ctx.getBeansOfType(SiteEventListener.class).keySet(), "one SOURCE and one CONSUMER listener");
            assertEquals(Set.of("runwayDemoLifecycle"), ctx.getBeansOfType(PluginLifecycle.class).keySet());
            assertNotNull(ctx.getBean("runwayDemoConsoleController", DemoConsoleController.class));
            assertNotNull(ctx.getBean("runwayDemoAppProps", DemoAppProps.class));

            DemoAdminModel model = ctx.getBean("runwayDemoAdminModel", DemoAdminModel.class);
            assertEquals(1, model.noteCount("acme"), "the notes store works over the injected site files");
            assertEquals("Acme", model.siteName("acme"), "…the site name comes from Sites");
            assertEquals("/demo-moved", model.demoPagePath("acme"), "…the page path from SitePages");
            assertTrue(model.siteTokenConfigured("acme"), "…the secret's presence from PluginSettings");
            assertEquals("2.0.1", model.pluginVersion(), "…the version from PluginInfo");
        }
    }

    @Test
    void theAdminModelIsNullSafeForTheApplicationConsole(@TempDir Path sites) {
        DemoAdminModel model = new DemoAdminModel(new DemoNotesStore(new TempDirSiteFiles(sites, "runway-demo")),
                new DemoAppProps(), new FakePluginSettings(), new FakeSiteServices(), new FakeSiteServices(),
                new PluginInfo("runway-demo", "2.0.1", sites));
        assertEquals(0, model.noteCount(null));
        assertEquals(List.of(), model.recentNotes(" "));
        assertEquals("", model.siteName(null));
        assertEquals("", model.demoPagePath(null));
        assertFalse(model.siteTokenConfigured(null));
        assertEquals("ghost", model.siteName("ghost"), "an unknown site shows its id");
        assertEquals(mx.quest.runway.spi.RunwaySpi.VERSION, model.builtSpi());
        assertEquals(mx.quest.runway.spi.RunwaySpi.runtimeVersion(), model.runningSpi());
    }

    @Test
    void everyBeanNameStartsWithThePluginIdInLowerCamelCase() {
        Set<String> names = beanMethodNames();
        assertEquals(Set.of("runwayDemoAppProps", "runwayDemoNotesStore", "runwayDemoShowcaseStore",
                "runwayDemoLifecycle", "runwayDemoActivity", "runwayDemoAnalyticsHead", "runwayDemoNoticeWidget",
                "runwayDemoFeedbackWidget", "runwayDemoMetaLengthCheck", "runwayDemoEndpoints",
                "runwayDemoShowcaseSource", "runwayDemoAdminModel", "runwayDemoConsoleController"), names);
        for (Method m : DemoPluginConfig.class.getDeclaredMethods()) {
            Bean bean = m.getAnnotation(Bean.class);
            if (bean != null) {
                assertEquals(0, bean.name().length + bean.value().length, "the method name is the bean name: " + m);
            }
        }
        assertFalse(AnnotatedElementUtils.hasAnnotation(DemoPluginConfig.class,
                        org.springframework.boot.context.properties.EnableConfigurationProperties.class),
                "@EnableConfigurationProperties would register a '<prefix>-<class>' bean name");
    }

    /** Servlet filters, security chains and post-processors are outside what a plugin may contribute. */
    @Test
    void noBeanIsOfAForbiddenSpringType() {
        Set<String> forbidden = Set.of("jakarta.servlet.Filter", "org.springframework.security.web.SecurityFilterChain",
                "org.springframework.boot.web.servlet.FilterRegistrationBean",
                "org.springframework.beans.factory.config.BeanPostProcessor",
                "org.springframework.beans.factory.config.BeanFactoryPostProcessor");
        for (Method m : DemoPluginConfig.class.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Bean.class)) {
                continue;
            }
            Deque<Class<?>> queue = new ArrayDeque<>(List.of(m.getReturnType()));
            Set<Class<?>> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                Class<?> type = queue.pop();
                if (!seen.add(type)) {
                    continue;
                }
                assertFalse(forbidden.contains(type.getName()), m.getName() + " is a " + type.getName());
                if (type.getSuperclass() != null) {
                    queue.add(type.getSuperclass());
                }
                queue.addAll(List.of(type.getInterfaces()));
            }
            assertFalse(AnnotatedElementUtils.hasAnnotation(m.getReturnType(),
                    org.springframework.web.bind.annotation.ControllerAdvice.class), "no @ControllerAdvice needed");
        }
    }

    @Test
    void theConsoleControllerIsAConsoleScreenGatedAndMappedUnderItsOwnPrefix() {
        Class<?> type = DemoConsoleController.class;
        assertTrue(AnnotatedElementUtils.hasAnnotation(type, Controller.class));
        assertTrue(type.isAnnotationPresent(ConsoleScreen.class), "the kernel fills the console shell for it");
        assertEquals(List.of("/__admin/runway-demo"),
                List.of(AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class).path()));
        int handlers = 0;
        int gets = 0;
        for (Method m : type.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            handlers++;
            PreAuthorize gate = m.getAnnotation(PreAuthorize.class);
            assertNotNull(gate, m.getName() + " must be gated");
            assertEquals("@authz.may('runway-demo|manage-notes')", gate.value());
            for (String path : mapping.path()) {
                assertTrue(path.isEmpty() || path.startsWith("/"), path);
            }
            if (m.isAnnotationPresent(GetMapping.class)) {
                gets++;
                assertEquals("screen", m.getName(), "only the read-only screen answers GET");
            }
        }
        assertEquals(6, handlers, "the screen and five POST actions");
        assertEquals(1, gets);
    }

    @Test
    void theTemplatesReferenceOnlyOurOwnBeansAndAuthz() throws IOException {
        Set<String> referenced = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("templates"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".th.xml")).toList()) {
                String live = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                Matcher m = Pattern.compile("@(\\w+)\\.").matcher(live);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            }
        }
        assertEquals(Set.of("authz", "runwayDemoAdminModel"), referenced);
        assertTrue(beanMethodNames().contains("runwayDemoAdminModel"));
    }

    @Test
    void theDescriptorDeclaresTheSpiFloorTheReleaseVersionAndTheConsoleMenu() throws IOException {
        Map<String, Object> yml = descriptor();
        assertEquals(">=1.0.0", yml.get("requiresSpi"), "built for the frozen SPI 1.0.0 contract");
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("<artifactId>runway-plugin-runway-demo</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(version.find());
        assertEquals("2.1.0", version.group(1));
        assertEquals(version.group(1), String.valueOf(yml.get("version")), "plugin.yml and pom.xml agree");
        assertFalse(pom.contains("<artifactId>runway-app</artifactId>"), "no runway-app dependency in any scope");
        assertTrue(pom.contains("<project.build.outputTimestamp>2026-10-21T17:23:00Z</project.build.outputTimestamp>"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> menu = (List<Map<String, Object>>) yml.get("menu");
        assertEquals(DemoConsoleController.BASE, menu.get(0).get("path"), "the menu opens the console screen");
        assertEquals(DemoEndpoints.PERMISSION, menu.get(0).get("permission"), "the FULL namespaced key");
    }

    @Test
    void theReleaseZipIsNamedAfterThePluginIdLikeEveryOtherPluginAndTheJarKeepsItsName() throws IOException {
        String id = String.valueOf(descriptor().get("id"));
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        // publish-plugin-release.sh uploads target/runway-plugin-<id>-<version>.zip under the tag <id>-<version>.
        assertTrue(pom.contains("<artifactId>runway-plugin-" + id + "</artifactId>"), "artifactId runway-plugin-" + id);
        assertTrue(pom.contains("<zip destfile=\"${project.build.directory}/${project.artifactId}-${project.version}"
                + ".zip\">"), "the zip is named after the artifactId");
        // The kernel loads every *.jar in the plugin's folder: a renamed jar copied over a 2.0.0 folder would load
        // beside the old one. So the jar keeps the name 2.0.0 shipped.
        assertTrue(pom.contains("<finalName>runway-plugin-demo</finalName>"), "the jar keeps its 2.0.0 name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNotesFileIsDeclaredPersonalDataSoTransferCopiesLeaveItOut() throws IOException {
        Map<String, Object> siteData = (Map<String, Object>) descriptor().get("siteData");
        assertNotNull(siteData, "plugin.yml declares siteData");
        assertEquals(List.of(DemoNotesStore.FILE), siteData.get("personal"),
                "exactly the file holding what people typed; the showcase is published content");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSiteTokenIsDeclaredASecretAndEveryReadSettingIsDeclared() throws IOException {
        List<Map<String, Object>> schema = (List<Map<String, Object>>) descriptor().get("siteSettings");
        Map<String, Object> types = schema.stream()
                .collect(Collectors.toMap(s -> (String) s.get("key"), s -> s.get("type")));
        assertEquals("secret", types.get("analytics.site-token"),
                "PluginSettings.secret hands out only keys declared type: secret");
        String code = readAll(Path.of("src/main/java"));
        Matcher reads = Pattern.compile("\\.(?:bool|string|integer|decimal|list)\\(\"([a-z.-]+)\"").matcher(code);
        int count = 0;
        while (reads.find()) {
            count++;
            assertTrue(types.containsKey(reads.group(1)), "read but not declared: " + reads.group(1));
        }
        assertTrue(count >= 8, "the per-site reads were found (" + count + ")");
    }

    private static String readAll(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                all.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }

    @Test
    void theEnglishAndSpanishBundlesDefineTheSameKeysAndEveryKeyUsedExists() throws IOException {
        Set<String> en = keys("i18n/messages.properties");
        assertEquals(en, keys("i18n/messages_es.properties"));
        Set<String> used = new TreeSet<>();
        Matcher code = Pattern.compile("\"(runwaydemo\\.[A-Za-z.]+)\"").matcher(readAll(Path.of("src/main/java")));
        while (code.find()) {
            used.add(code.group(1));
        }
        Matcher template = Pattern.compile("#\\{(runwaydemo\\.[A-Za-z.]+)").matcher(readAll(Path.of("templates")));
        while (template.find()) {
            used.add(template.group(1));
        }
        Matcher descriptor = Pattern.compile("label: (runwaydemo\\.[A-Za-z.]+)")
                .matcher(Files.readString(Path.of("plugin.yml"), StandardCharsets.UTF_8));
        while (descriptor.find()) {
            used.add(descriptor.group(1));
        }
        used.removeIf(en::contains);
        assertEquals(Set.of(), used, "keys used but missing from the bundle");
    }

    private static Set<String> keys(String file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new TreeSet<>(p.stringPropertyNames());
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
                // The constant pool holds class names and descriptors as (modified) UTF-8; Latin-1 keeps every byte
                // as one char, so an ASCII pattern finds them without parsing the class file.
                Matcher m = host.matcher(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    problems.add(rel + " → " + m.group());
                }
            }
        }
        assertTrue(scanned >= 18, "the compiled classes were found (" + scanned + ")");
        assertTrue(problems.isEmpty(), "host linkage: " + problems);
    }

    @Test
    void theRemovedSeamsAreGone() {
        for (String gone : List.of("DemoTimingFilter", "DemoNotesBackup", "DemoConfigReactor", "SiteConfig")) {
            assertNull(classOrNull("mx.quest.demo." + gone), gone + " was removed in 2.0.0");
        }
        assertInstanceOf(PluginLifecycle.class, new DemoLifecycle());
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
