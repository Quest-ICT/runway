package mx.quest.searchlucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.LinkedHashMap;
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
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.service.Sites;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.search.SiteSearchCorpus;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SiteSearchEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * The plugin's contract with the host and with the search plugin, checked at build time: the wiring over published
 * services only (SPI services from the kernel, {@link SearchCorpus} from search) including the app-level storage
 * settings bound under {@code runway.plugin.search-lucene}; the bean-name rule; the bean names the templates reference;
 * the descriptor (SPI floor, release version, the {@code requires:} gates); the code fallbacks equal to the descriptor
 * defaults; the i18n bundles; and that the compiled classes link nothing of the host but the SPI and nothing of the
 * search plugin but its SPI package — the same things the kernel's load-time checks warn about (and refuse from
 * Runway 1.0).
 */
class SearchLuceneConfigTest {

    private static final String PREFIX = "searchLucene";

    /** Stands in for the host's Spring Boot auto-configuration: the binder behind {@code @ConfigurationProperties}. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class HostBinding {
    }

    private static Set<String> beanMethodNames() {
        return Arrays.stream(SearchLuceneConfig.class.getDeclaredMethods())
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
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) descriptor().get(section)) {
            if (s.containsKey("default")) {
                out.put((String) s.get("key"), s.get("default"));
            }
        }
        return out;
    }

    @Test
    void wiresEverythingFromPublishedServicesUnderPrefixedNames(@org.junit.jupiter.api.io.TempDir Path index)
            throws IOException {
        FakeSiteServices kernel = new FakeSiteServices()
                .site(new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null));
        FakeContentCatalog catalog = new FakeContentCatalog()
                .entry("acme", LuceneSearchEngineTest.item("home", "Runway home", ""))
                .entry("acme", LuceneSearchEngineTest.item("privacy", "Runway privacy", ""));
        SearchCorpus corpus = new SiteSearchCorpus(catalog,
                new FakePluginSettings().site("acme", Map.of("exclude", List.of("privacy"))));
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("plugin-settings", Map.of(
                    "runway.plugin.search-lucene.storage", "disk",
                    "runway.plugin.search-lucene.indexRoot", index.toString())));
            ctx.registerBean(PluginSettings.class, () -> new FakePluginSettings()
                    .site("acme", Map.of("titleBoost", 9.5)));
            ctx.registerBean(Sites.class, () -> kernel);
            ctx.registerBean(SearchCorpus.class, () -> corpus);
            ctx.register(HostBinding.class, SearchLuceneConfig.class);
            ctx.refresh();

            LuceneStorageProps props = ctx.getBean("searchLuceneStorageProps", LuceneStorageProps.class);
            assertTrue(props.isDisk(), "the app-level settings bind under runway.plugin.search-lucene");
            assertEquals(index.toString(), props.getIndexRoot());

            LuceneSearchEngine engine = ctx.getBean("searchLuceneEngine", LuceneSearchEngine.class);
            assertEquals(List.of(engine), List.copyOf(ctx.getBeansOfType(SiteSearchEngine.class).values()),
                    "one engine, collected by the search plugin's List<SiteSearchEngine>");
            assertEquals(1, engine.indexedDocuments("acme"), "the engine indexes the corpus (privacy is excluded)");
            assertEquals("disk", engine.storageMode());
            assertTrue(Files.isDirectory(index.resolve("acme")));

            Map<String, SiteEventListener> listeners = ctx.getBeansOfType(SiteEventListener.class);
            assertEquals(Set.of("searchLuceneSiteEvents"), listeners.keySet(), "one listener, collected by type");
            assertEquals(SiteEventListener.Phase.CONSUMER, listeners.get("searchLuceneSiteEvents").phase());

            LuceneAdminModel model = ctx.getBean("searchLuceneAdminModel", LuceneAdminModel.class);
            assertEquals("9.5", model.titleBoost("acme"), "the knobs come from the injected PluginSettings");
            assertSame(engine, readEngine(model));

            for (String name : ctx.getBeanDefinitionNames()) {
                Class<?> type = ctx.getType(name);
                if (type != null && type.getPackageName().equals("mx.quest.searchlucene")
                        && !name.equals(SearchLuceneConfig.class.getName())
                        && !name.equals("searchLuceneConfig")) {
                    assertTrue(name.startsWith(PREFIX), "every plugin bean name starts with 'searchLucene': " + name);
                }
            }
        }
    }

    private static Object readEngine(LuceneAdminModel model) {
        try {
            var field = LuceneAdminModel.class.getDeclaredField("engine");
            field.setAccessible(true);
            return field.get(model);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void everyBeanNameStartsWithThePluginIdInLowerCamelCase() {
        assertEquals(Set.of("searchLuceneStorageProps", "searchLuceneEngine", "searchLuceneSiteEvents",
                "searchLuceneAdminModel"), beanMethodNames());
        for (Method m : SearchLuceneConfig.class.getDeclaredMethods()) {
            Bean bean = m.getAnnotation(Bean.class);
            if (bean != null) {
                for (String alias : bean.name()) {
                    assertTrue(alias.startsWith(PREFIX), "an explicit bean name must start with " + PREFIX + ": " + alias);
                }
            }
        }
        assertFalse(SearchLuceneConfig.class.isAnnotationPresent(EnableConfigurationProperties.class),
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
        assertEquals(Set.of("searchLuceneAdminModel"), referenced, "the console templates read the admin model only");
        assertTrue(beanMethodNames().containsAll(referenced), "templates reference unknown beans: " + referenced);
    }

    @Test
    void theDescriptorDeclaresTheFloorsAndTheReleaseVersion() throws IOException {
        Map<String, Object> yml = descriptor();
        assertEquals(">=1.0.0", yml.get("requiresSpi"), "built for the frozen SPI 1.0.0 contract");
        assertEquals(List.of("search >= 2.2.0", "lucene-provider == 10.4.0"), yml.get("requires"),
                "search 2.2.0 submits a localized box to the localized results page, which reaches a language's index"
                        + " (2.1.0 was the first with the matched-words SearchHit); Lucene is pinned to the binaries");
        assertEquals("mx.quest.searchlucene.SearchLuceneConfig", yml.get("mainClass"));
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("<artifactId>runway-plugin-search-lucene</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(version.find());
        assertEquals(version.group(1), String.valueOf(yml.get("version")), "plugin.yml and pom.xml agree on the version");
        assertEquals("3.3.0", version.group(1));
        assertFalse(pom.contains("<artifactId>runway-app</artifactId>"), "no runway-app dependency in any scope");
        assertTrue(pom.contains("<project.build.outputTimestamp>2026-10-21T17:23:00Z</project.build.outputTimestamp>"));
        assertTrue(pom.contains("<lucene.version>10.4.0</lucene.version>"),
                "the compiled-against Lucene equals the lucene-provider pin");
        assertFalse(yml.containsKey("siteData"), "the plugin keeps no per-site data (the index is derived)");
    }

    @Test
    void theCodeFallbacksEqualTheDescriptorDefaults() throws IOException {
        Map<String, Object> site = declaredDefaults("siteSettings");
        RankingConfig code = RankingConfig.defaults();
        assertEquals(site.get("fuzzy"), code.fuzzy());
        assertEquals(((Number) site.get("fuzzyBoost")).floatValue(), code.fuzzyBoost());
        assertEquals(((Number) site.get("titleBoost")).floatValue(), code.titleBoost());
        assertEquals(((Number) site.get("descriptionBoost")).floatValue(), code.descriptionBoost());
        assertEquals(((Number) site.get("tierPhraseTitle")).floatValue(), code.tierPhraseTitle());
        assertEquals(((Number) site.get("tierPhraseBody")).floatValue(), code.tierPhraseBody());
        assertEquals(((Number) site.get("tierNearPhrase")).floatValue(), code.tierNearPhrase());
        assertEquals(((Number) site.get("tierAllTerms")).floatValue(), code.tierAllTerms());
        assertEquals(8, site.size(), "every declared default is checked above");
        assertEquals(code, RankingConfig.from(site), "the declared defaults resolve to the code defaults");

        Map<String, Object> app = declaredDefaults("settings");
        LuceneStorageProps props = new LuceneStorageProps();
        assertEquals(app.get("storage"), props.getStorage().name().toLowerCase(Locale.ROOT));
        assertEquals(app.get("indexRoot"), props.getIndexRoot());
    }

    @Test
    void theEnglishAndSpanishBundlesDefineTheSameKeys() throws IOException {
        Set<String> en = keys("i18n/messages.properties");
        assertEquals(en, keys("i18n/messages_es.properties"));
        Set<String> labels = new TreeSet<>();
        for (String section : List.of("settings", "siteSettings", "permissions")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) descriptor().get(section);
            entries.forEach(e -> labels.add((String) e.get("label")));
        }
        assertTrue(en.containsAll(labels), "every descriptor label has words: " + labels);
    }

    private static Set<String> keys(String file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new TreeSet<>(p.stringPropertyNames());
    }

    /** Every class file this plugin ships, with its constant pool as text (Latin-1 keeps one char per byte). */
    private static Map<String, String> compiledClasses() throws IOException {
        Path classes = Path.of("target", "classes");
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                out.put(classes.relativize(file).toString().replace('\\', '/'),
                        new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }

    @Test
    void theCompiledClassesLinkNoHostInternalsAndOnlyTheSearchSpi() throws IOException {
        Pattern host = Pattern.compile("mx[/.]quest[/.]runway[/.](?!spi[/.])[\\w/.$]*");
        // search's own classes (SiteSearchCorpus, SearchSiteConfig, …) are its internals: only its SPI package is a
        // contract, and only the SPI package is guaranteed to keep its shape across search releases.
        Pattern searchInternals = Pattern.compile("mx[/.]quest[/.]search[/.](?!spi[/.])[\\w/.$]*");
        List<String> problems = new ArrayList<>();
        Map<String, String> classes = compiledClasses();
        classes.forEach((rel, pool) -> {
            if (!rel.startsWith("mx/quest/searchlucene/")) {
                problems.add(rel + " lives outside mx.quest.searchlucene");
            }
            for (Pattern p : List.of(host, searchInternals)) {
                Matcher m = p.matcher(pool);
                while (m.find()) {
                    problems.add(rel + " → " + m.group());
                }
            }
        });
        assertTrue(classes.size() >= 8, "the compiled classes were found (" + classes.size() + ")");
        assertTrue(problems.isEmpty(), "linkage outside the published contracts: " + problems);
        assertTrue(classes.values().stream().anyMatch(pool -> pool.contains("mx/quest/search/spi/SearchCorpus")),
                "the engine reads the search plugin's SearchCorpus");
    }

    @Test
    void theCompiledClassesLinkNoCaffeine() throws IOException {
        // Caffeine is a kernel implementation detail, not one of the platform libraries the kernel promises
        // (Spring, Spring Boot, Spring Security, Thymeleaf, Jackson, Jakarta EE): a plugin must not link it.
        compiledClasses().forEach((rel, pool) ->
                assertFalse(pool.contains("com/github/benmanes"), rel + " links Caffeine"));
    }
}
