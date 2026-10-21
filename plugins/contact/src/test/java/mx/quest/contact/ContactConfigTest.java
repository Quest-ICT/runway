package mx.quest.contact;

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
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.CaptchaWidget;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.Yaml;

/**
 * The plugin's contract with the host, checked at build time: the wiring over SPI services only, the bean-name
 * rule, the bean names the templates reference, the descriptor (SPI floor, release version, the personal-data
 * declaration matching the file the store writes), the i18n bundles, and that the compiled classes link nothing of
 * the host but the SPI — the same things the kernel's load-time checks warn about (and refuse from Runway 1.0).
 */
class ContactConfigTest {

    private static final String PREFIX = "contact";

    private static Set<String> beanMethodNames() {
        return Arrays.stream(ContactConfig.class.getDeclaredMethods())
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
    void wiresEverythingFromSpiServicesUnderPrefixedNames(@TempDir Path sites) throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        FakeSiteServices kernel = new FakeSiteServices()
                .site(new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null))
                .page("acme", "contact", "/kontakt");
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PluginSettings.class, () -> new FakePluginSettings()
                    .site("acme", Map.of("smtpHost", "mail.example.com", "toAddress", "owner@example.com")));
            ctx.registerBean(PluginSiteFiles.class, () -> new TempDirSiteFiles(sites, "contact"));
            ctx.registerBean(FakeSiteServices.class, () -> kernel);
            ctx.registerBean(CaptchaService.class, () -> new CaptchaService() {
                @Override
                public CaptchaWidget widgetForSite(String siteId) {
                    return CaptchaWidget.inactive();
                }

                @Override
                public boolean verify(String siteId, String token, String clientIp) {
                    return true;
                }
            });
            ctx.register(ContactConfig.class);
            ctx.refresh();

            assertInstanceOf(ContactWidget.class, ctx.getBean("contactWidget", ContentWidget.class));
            assertInstanceOf(ContactEndpoint.class, ctx.getBean("contactEndpoint", EndpointContributor.class));
            ctx.getBean("contactStore", ContactStore.class).add("acme", "Ann", "a@example.com", "hi", 1L);
            ContactAdminModel model = ctx.getBean("contactAdminModel", ContactAdminModel.class);
            assertEquals(1, model.count("acme"), "the admin model reads the store over the injected site files");
            assertTrue(model.smtpConfigured("acme"), "…and the relay over the injected settings");
            assertEquals("/kontakt", model.pagePath("acme"), "…and the page through SitePages");
            assertTrue(model.pageInstalled("acme"));
            assertFalse(model.pageInstalled("other"));
        }
    }

    @Test
    void everyBeanNameStartsWithThePluginIdInLowerCamelCase() {
        Set<String> names = beanMethodNames();
        assertEquals(Set.of("contactStore", "contactMailTransport", "contactMailer", "contactWidget",
                "contactEndpoint", "contactAdminModel"), names);
        for (Method m : ContactConfig.class.getDeclaredMethods()) {
            Bean bean = m.getAnnotation(Bean.class);
            if (bean != null) {
                for (String alias : bean.name()) {
                    assertTrue(alias.startsWith(PREFIX), "an explicit bean name must start with " + PREFIX + ": " + alias);
                }
            }
        }
    }

    @Test
    void everyBeanTheTemplatesReferenceIsOneOfOurs() throws IOException {
        Set<String> referenced = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("templates"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".th.xml")).toList()) {
                // XML comments explain the ${@bean} rules in prose; only the live attributes count.
                String live = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                Matcher m = Pattern.compile("\\$\\{\\s*!?\\s*@(\\w+)").matcher(live);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            }
        }
        assertEquals(Set.of("contactAdminModel"), referenced, "the console templates read the admin model only");
        assertTrue(beanMethodNames().containsAll(referenced), "templates reference unknown beans: " + referenced);
    }

    @Test
    void theDescriptorDeclaresTheSpiFloorAndTheReleaseVersion() throws IOException {
        Map<String, Object> yml = descriptor();
        assertEquals(">=1.0.0", yml.get("requiresSpi"), "built for the frozen SPI 1.0.0 contract");
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("<artifactId>runway-plugin-contact</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(version.find());
        assertEquals(version.group(1), String.valueOf(yml.get("version")), "plugin.yml and pom.xml agree on the version");
        assertFalse(pom.contains("<artifactId>runway-app</artifactId>"), "no runway-app dependency in any scope");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSubmissionsFileIsDeclaredPersonalDataSoTransferCopiesLeaveItOut() throws IOException {
        Map<String, Object> siteData = (Map<String, Object>) descriptor().get("siteData");
        assertTrue(siteData != null, "plugin.yml declares siteData");
        List<String> personal = (List<String>) siteData.get("personal");
        assertEquals(List.of(ContactStore.FILE), personal,
                "exactly the file the store writes (visitor names, emails, messages) is personal data");
    }

    @Test
    void theSmtpPasswordIsDeclaredASecret() throws IOException {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schema = (List<Map<String, Object>>) descriptor().get("siteSettings");
        Map<String, Object> password = schema.stream()
                .filter(s -> ContactMailer.PASSWORD_KEY.equals(s.get("key"))).findFirst().orElseThrow();
        assertEquals("secret", password.get("type"),
                "PluginSettings.secret hands out only keys declared type: secret (and site() leaves them out)");
    }

    @Test
    void theSmtpPasswordIsBoundToTheRelayLogin() throws IOException {
        // secretBoundTo: a save that changes the relay's host, port or username without retyping the password makes
        // the kernel drop the stored password, so a settings editor cannot repoint the saved credentials at a server
        // of their choosing. Every bound key must be a declared setting, or the binding silently binds nothing.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schema = (List<Map<String, Object>>) descriptor().get("siteSettings");
        Map<String, Object> password = schema.stream()
                .filter(s -> ContactMailer.PASSWORD_KEY.equals(s.get("key"))).findFirst().orElseThrow();
        assertEquals(List.of("smtpHost", "smtpPort", "smtpUsername", "smtpStarttls", "smtpSsl"), password.get("secretBoundTo"),
                "the stored SMTP password is bound to the host, port and username it was typed for");
        List<Object> declared = schema.stream().map(s -> s.get("key")).toList();
        for (Object bound : (List<?>) password.get("secretBoundTo")) {
            assertTrue(declared.contains(bound), "secretBoundTo names a declared setting: " + bound);
        }
    }

    @Test
    void theEnglishAndSpanishBundlesDefineTheSameKeys() throws IOException {
        assertEquals(keys("i18n/messages.properties"), keys("i18n/messages_es.properties"));
        Set<String> en = keys("i18n/messages.properties");
        for (String key : List.of("contact.page.installed", "contact.page.pending", "contact.page.blocked",
                "contact.page.exists", "contact.page.failed", "contact.page.other")) {
            assertTrue(en.contains(key), "every install outcome has words: " + key);
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
        assertTrue(scanned >= 12, "the compiled classes were found (" + scanned + ")");
        assertTrue(problems.isEmpty(), "host linkage: " + problems);
    }
}
