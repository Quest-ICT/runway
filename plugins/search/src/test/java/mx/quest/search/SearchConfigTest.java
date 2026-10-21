package mx.quest.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SiteSearchEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * The plugin's contract with the host, checked at build time: the wiring over SPI services only (including the
 * app-level properties bound under {@code runway.plugin.search}), the bean-name rule, the bean names the templates
 * reference, the descriptor (SPI floor, release version, the personal-data declaration matching the file the log
 * writes), the code fallbacks equal to the descriptor defaults, the i18n bundles, and that the compiled classes link
 * nothing of the host but the SPI — the same things the kernel's load-time checks warn about (and refuse from
 * Runway 1.0).
 */
class SearchConfigTest {

    private static final String PREFIX = "search";

    /** Stands in for the host's Spring Boot auto-configuration: the binder behind {@code @ConfigurationProperties}. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class HostBinding {
    }

    private static Set<String> beanMethodNames() {
        return Arrays.stream(SearchConfig.class.getDeclaredMethods())
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> declaredDefaults(String section) throws IOException {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) descriptor().get(section)) {
            if (s.containsKey("default")) {
                out.put((String) s.get("key"), s.get("default"));
            }
        }
        return out;
    }

    @Test
    void wiresEverythingFromSpiServicesUnderPrefixedNames(@TempDir Path sites) throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        FakeSiteServices kernel = new FakeSiteServices()
                .site(new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null))
                .page("acme", "search", "/buscar");
        FakeContentCatalog catalog = new FakeContentCatalog()
                .entry("acme", Fixtures.page("home", "Runway home", ""))
                .entry("acme", Fixtures.page("privacy", "Runway privacy", ""));
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("plugin-settings", Map.of(
                    "runway.plugin.search.refresh-minutes", "5",
                    "runway.plugin.search.rate-limit.per-ip-per-minute", "7",
                    "runway.plugin.search.rate-limit.per-ip-per-hour", "70")));
            ctx.registerBean(PluginSettings.class, () -> new FakePluginSettings()
                    .site("acme", Map.of("exclude", List.of("privacy"))));
            ctx.registerBean(PluginSiteFiles.class, () -> new TempDirSiteFiles(sites, "search"));
            ctx.registerBean(FakeSiteServices.class, () -> kernel);
            ctx.registerBean(ContentCatalogView.class, () -> catalog);
            ctx.register(HostBinding.class, SearchConfig.class);
            ctx.refresh();

            SearchProperties props = ctx.getBean("searchProperties", SearchProperties.class);
            assertEquals(5, props.getRefreshMinutes(), "the app-level settings bind under runway.plugin.search");
            assertEquals(7, props.getRateLimit().getPerIpPerMinute());
            assertEquals(70, props.getRateLimit().getPerIpPerHour());

            assertInstanceOf(SiteSearchCorpus.class, ctx.getBean("searchCorpus", SearchCorpus.class));
            assertEquals(List.of(ctx.getBean("searchSimpleEngine")),
                    List.copyOf(ctx.getBeansOfType(SiteSearchEngine.class).values()));
            Map<String, SiteEventListener> listeners = ctx.getBeansOfType(SiteEventListener.class);
            assertEquals(Set.of("searchSiteEvents"), listeners.keySet(), "one listener, collected by type");
            assertEquals(SiteEventListener.Phase.CONSUMER, listeners.get("searchSiteEvents").phase());
            assertInstanceOf(SearchEndpoint.class, ctx.getBean("searchResultsEndpoint", EndpointContributor.class));
            assertInstanceOf(SearchBoxWidget.class, ctx.getBean("searchBoxWidget", ContentWidget.class));

            SearchAdminModel model = ctx.getBean("searchAdminModel", SearchAdminModel.class);
            assertEquals(1, model.searchableItems("acme"), "the corpus reads the catalog and the injected settings");
            assertEquals("/buscar", model.pagePath("acme"), "…and the page through SitePages");
            assertEquals("search/simple", model.activeEngineKey("acme"));

            for (String name : ctx.getBeanDefinitionNames()) {
                Class<?> type = ctx.getType(name);
                if (type != null && type.getPackageName().startsWith("mx.quest.search")) {
                    assertTrue(name.startsWith(PREFIX), "every plugin bean name starts with 'search': " + name);
                }
            }
        }
    }

    @Test
    void everyBeanNameStartsWithThePluginIdInLowerCamelCase() {
        assertEquals(Set.of("searchProperties", "searchCorpus", "searchSimpleEngine", "searchRateLimiter",
                "searchNoResultsLog", "searchSiteEvents", "searchResultsEndpoint", "searchBoxWidget",
                "searchHighlightHead", "searchAdminModel"), beanMethodNames());
        for (Method m : SearchConfig.class.getDeclaredMethods()) {
            Bean bean = m.getAnnotation(Bean.class);
            if (bean != null) {
                for (String alias : bean.name()) {
                    assertTrue(alias.startsWith(PREFIX), "an explicit bean name must start with " + PREFIX + ": " + alias);
                }
            }
        }
        assertFalse(SearchConfig.class.isAnnotationPresent(EnableConfigurationProperties.class),
                "@EnableConfigurationProperties names its bean '<prefix>-<class>', which breaks the bean-name rule");
    }

    @Test
    void everyBeanTheTemplatesReferenceIsOneOfOurs() throws IOException {
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
        assertEquals(Set.of("searchAdminModel"), referenced, "the console templates read the admin model only");
        assertTrue(beanMethodNames().containsAll(referenced), "templates reference unknown beans: " + referenced);
    }

    @Test
    void theDescriptorDeclaresTheSpiFloorAndTheReleaseVersion() throws IOException {
        Map<String, Object> yml = descriptor();
        assertEquals(">=1.0.0", yml.get("requiresSpi"), "built for the frozen SPI 1.0.0 contract");
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("<artifactId>runway-plugin-search</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(version.find());
        assertEquals(version.group(1), String.valueOf(yml.get("version")), "plugin.yml and pom.xml agree on the version");
        assertEquals("2.3.0", version.group(1));
        assertEquals(version.group(1), SearchConfig.VERSION, "the ?v= on the plugin's file URLs is this release");
        assertFalse(pom.contains("<artifactId>runway-app</artifactId>"), "no runway-app dependency in any scope");
        assertTrue(pom.contains("<project.build.outputTimestamp>2026-10-21T17:23:00Z</project.build.outputTimestamp>"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNoResultsFileIsDeclaredPersonalDataSoTransferCopiesLeaveItOut() throws IOException {
        Map<String, Object> siteData = (Map<String, Object>) descriptor().get("siteData");
        assertTrue(siteData != null, "plugin.yml declares siteData");
        assertEquals(List.of(NoResultsLog.FILE), siteData.get("personal"),
                "exactly the file the no-result log writes (search terms visitors typed) is personal data");
    }

    @Test
    void theCodeFallbacksEqualTheDescriptorDefaults() throws IOException {
        Map<String, Object> site = declaredDefaults("siteSettings");
        SearchSiteConfig code = SearchSiteConfig.of(SettingsView.empty());
        assertEquals(site.get("enabled"), code.enabled());
        assertEquals(site.get("resultLimit"), code.resultLimit());
        assertEquals(site.get("resultsPath"), code.resultsPath());
        assertEquals(site.get("snippetLength"), code.snippetLength());
        assertEquals(site.get("highlight"), code.highlight());
        assertEquals(site.get("titleWeight"), code.titleWeight());
        assertEquals(site.get("descriptionWeight"), code.descriptionWeight());
        assertEquals(site.get("bodyWeight"), code.bodyWeight());
        assertEquals(site.get("allTermsBonus"), code.allTermsBonus());
        assertEquals(site.get("phraseTitleBonus"), code.phraseTitleBonus());
        assertEquals(site.get("phraseBodyBonus"), code.phraseBodyBonus());
        assertEquals(site.get(NoResultsLog.ENABLED_KEY), false, "recording is off by default");
        assertEquals(site.get(NoResultsLog.RETENTION_KEY), NoResultsLog.DEFAULT_RETENTION_DAYS);
        assertEquals(13, site.size(), "every declared default is checked above (exclude/placeholder have none)");

        Map<String, Object> app = declaredDefaults("settings");
        SearchProperties props = new SearchProperties();
        assertEquals(app.get("refresh-minutes"), props.getRefreshMinutes());
        assertEquals(app.get("rate-limit.per-ip-per-minute"), props.getRateLimit().getPerIpPerMinute());
        assertEquals(app.get("rate-limit.per-ip-per-hour"), props.getRateLimit().getPerIpPerHour());
    }

    @Test
    void theEnglishAndSpanishBundlesDefineTheSameKeys() throws IOException {
        assertEquals(keys("i18n/messages.properties"), keys("i18n/messages_es.properties"));
        Set<String> en = keys("i18n/messages.properties");
        for (String key : List.of("search.page.installed", "search.page.pending", "search.page.blocked",
                "search.page.exists", "search.page.failed", "search.page.other", "search.reindex.done",
                "search.reindex.button", "search.reindex.tip", "search.reindex.desc")) {
            assertTrue(en.contains(key), "every operator outcome has words: " + key);
        }
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
                // The constant pool holds class names and descriptors as (modified) UTF-8; Latin-1 keeps every
                // byte as one char, so an ASCII pattern finds them without parsing the class file.
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
    void theCompiledClassesLinkNoCaffeine() throws IOException {
        // Caffeine is a kernel implementation detail, not one of the platform libraries the kernel promises
        // (Spring, Spring Boot, Spring Security, Thymeleaf, Jackson, Jakarta EE): a plugin must not link it.
        Path classes = Path.of("target", "classes");
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String pool = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(pool.contains("com/github/benmanes"), file + " links Caffeine");
            }
        }
    }
}
