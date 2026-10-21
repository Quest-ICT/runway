package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.service.Sites;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

/**
 * The plugin's contract with the host, checked at build time: the wiring over SPI services only, the bean-name rule,
 * the bean names the templates reference, the app-level settings binding, the descriptor (SPI floor, release
 * version) and that the compiled classes link nothing of the host but the SPI — the same things the kernel's
 * load-time checks warn about (and refuse from Runway 1.0).
 */
class DiscoverabilityConfigTest {

    private static final String PREFIX = "discoverability";
    private static final Locale EN = Locale.ENGLISH;

    private static Set<String> beanMethodNames() {
        return Arrays.stream(DiscoverabilityConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void wiresEverythingFromSpiServicesUnderPrefixedNamesAndBindsTheAppSettings() {
        SiteView acme = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        FakeContentCatalog catalog = new FakeContentCatalog().entry("acme", Entries.page(
                ContentRef.of("about", "/about", Map.of(), List.of(EN), Map.of("en", "About"), Map.of(),
                        "__site__/acme/about", null), acme));
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            // What the host provides: the SPI services, and Spring Boot's @ConfigurationProperties binding.
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("app-level", Map.of(
                    "runway.plugin.discoverability.indexnow.enabled", "true",
                    "runway.plugin.discoverability.indexnow.max-urls-per-submission", "7",
                    "runway.plugin.discoverability.robots.ai-default", "disallow")));
            ConfigurationPropertiesBindingPostProcessor.register(ctx);
            ctx.registerBean(PluginSettings.class, () -> TestSettings.declared().site("acme", Map.of("org.name", "Acme")));
            ctx.registerBean(ContentCatalogView.class, () -> catalog);
            ctx.registerBean(Sites.class, () -> new FakeSiteServices().site(acme));
            ctx.register(DiscoverabilityConfig.class);
            ctx.refresh();

            for (String name : ctx.getBeanDefinitionNames()) {
                if (ctx.getBeanFactory().getBeanDefinition(name).getFactoryBeanName() != null) {
                    assertTrue(name.startsWith(PREFIX), "every plugin bean carries the plugin id: " + name);
                }
            }
            assertInstanceOf(StructuredDataHead.class, ctx.getBean("discoverabilityStructuredData", HeadContributor.class));
            assertInstanceOf(SitemapEndpoint.class, ctx.getBean("discoverabilitySitemap", EndpointContributor.class));
            assertInstanceOf(LlmsTxtEndpoint.class, ctx.getBean("discoverabilityLlms", EndpointContributor.class));
            assertInstanceOf(RobotsTxtEndpoint.class, ctx.getBean("discoverabilityRobots", EndpointContributor.class));
            assertInstanceOf(IndexNowKeyEndpoint.class,
                    ctx.getBean("discoverabilityIndexNowKey", EndpointContributor.class));
            assertInstanceOf(FaqAdvisoryCheck.class, ctx.getBean("discoverabilityFaqAdvisory", PageQualityCheck.class));
            assertEquals(List.of("discoverabilityIndexNow"), List.of(ctx.getBeanNamesForType(SiteEventListener.class)),
                    "IndexNow is collected by the host as a SiteEventListener");

            DiscoverabilityProperties props = ctx.getBean("discoverabilityProperties", DiscoverabilityProperties.class);
            assertTrue(props.getIndexnow().isEnabled(), "app-level settings bind through the named @Bean");
            assertEquals(7, props.getIndexnow().getMaxUrlsPerSubmission());
            assertEquals("disallow", props.getRobots().getAiDefault());
            assertEquals(1, ctx.getBeanNamesForType(DiscoverabilityProperties.class).length,
                    "one properties bean — no second one under @EnableConfigurationProperties' generated name");

            DiscoverAdminModel model = ctx.getBean("discoverabilityAdminModel", DiscoverAdminModel.class);
            assertEquals(1, model.pageCount("acme"), "the admin model counts the injected listing");
            assertTrue(model.orgConfigured("acme"), "…and reads the injected settings");
            assertEquals(0, model.pageCount("other"));
            assertEquals(0, model.pageCount(null), "no tenant bound → 0, not an error page");
            assertEquals(0, model.pageCount("../x"), "a malformed id → 0, not an error page");
            assertTrue(model.jsonldEnabled(null), "no tenant → the defaults");
        }
    }

    @Test
    void everyBeanNameStartsWithThePluginIdInLowerCamelCase() {
        assertEquals(Set.of("discoverabilityProperties", "discoverabilityStructuredData", "discoverabilitySitemap",
                "discoverabilityLlms", "discoverabilityRobots", "discoverabilityIndexNowKey", "discoverabilityIndexNow",
                "discoverabilityFaqAdvisory", "discoverabilityPageType", "discoverabilityAdminModel"),
                beanMethodNames());
        for (Method m : DiscoverabilityConfig.class.getDeclaredMethods()) {
            Bean bean = m.getAnnotation(Bean.class);
            if (bean != null) {
                assertEquals(0, bean.name().length + bean.value().length,
                        "the bean name is the method name (" + m.getName() + "), never an alias to keep in sync");
            }
        }
        assertFalse(DiscoverabilityConfig.class.isAnnotationPresent(
                        org.springframework.boot.context.properties.EnableConfigurationProperties.class),
                "@EnableConfigurationProperties would register an unprefixed bean name");
    }

    @Test
    void everyBeanTheTemplatesReferenceIsOneOfOurs() throws IOException {
        Set<String> referenced = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("templates"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".th.xml")).toList()) {
                String live = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                Matcher m = Pattern.compile("\\$\\{\\s*!?\\s*@(\\w+)").matcher(live);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            }
        }
        assertEquals(Set.of("discoverabilityAdminModel"), referenced, "the console templates read the admin model only");
        assertTrue(beanMethodNames().containsAll(referenced), "templates reference unknown beans: " + referenced);
    }

    @Test
    void theDescriptorDeclaresTheSpiFloorAndTheReleaseVersion() throws IOException {
        Map<String, Object> yml = TestSettings.descriptor();
        assertEquals(">=1.0.0", yml.get("requiresSpi"),
                "HeadContext.origin() (a host-less site's absolute JSON-LD) arrived in SPI 1.0.0");
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("<artifactId>runway-plugin-discoverability</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(version.find());
        assertEquals("2.3.0", version.group(1));
        assertEquals(version.group(1), String.valueOf(yml.get("version")), "plugin.yml and pom.xml agree");
        String defaultBuild = pom.substring(0, pom.indexOf("<profiles>"));
        assertFalse(defaultBuild.contains("<artifactId>runway-app</artifactId>"),
                "no runway-app dependency outside the host-it profile");
        assertTrue(pom.contains("<project.build.outputTimestamp>2026-10-21T18:56:00Z</project.build.outputTimestamp>"));
    }

    @Test
    void thePluginStoresNoPerSiteFilesSoItDeclaresNoPersonalData() {
        assertFalse(TestSettings.descriptor().containsKey("siteData"),
                "nothing under sites/<id>/plugins/discoverability/ — its settings file is not personal data");
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
        assertTrue(scanned >= 25, "the compiled classes were found (" + scanned + ")");
        assertTrue(problems.isEmpty(), "host linkage: " + problems);
    }
}
