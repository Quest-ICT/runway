package mx.quest.carousel;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The plugin's contract with the host, checked at build time: the wiring, the bean-name rule, the bean names the
 * templates reference, the descriptor floor, and that the compiled classes link nothing of the host but the SPI —
 * the same things the kernel's load-time checks refuse.
 */
class CarouselConfigTest {

    private static final String PREFIX = "carouselManager";

    private static Set<String> beanMethodNames() {
        return Arrays.stream(CarouselConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void wiresTheWidgetAndTheAdminModelUnderPrefixedNames() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PluginSettings.class,
                    () -> new FakePluginSettings().site("acme", Map.of("slides", "/img/a.jpg\n/img/b.jpg")));
            ctx.register(CarouselConfig.class);
            ctx.refresh();
            assertInstanceOf(CarouselWidget.class, ctx.getBean("carouselManagerWidget", ContentWidget.class));
            assertEquals(2, ctx.getBean("carouselManagerAdminModel", CarouselAdminModel.class).slideCount("acme"),
                    "the admin model reads the injected settings");
        }
    }

    @Test
    void everyBeanNameStartsWithThePluginIdInLowerCamelCase() {
        Set<String> names = beanMethodNames();
        assertEquals(Set.of("carouselManagerWidget", "carouselManagerAdminModel"), names);
        for (Method m : CarouselConfig.class.getDeclaredMethods()) {
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
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\\$\\{\\s*@(\\w+)").matcher(text);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            }
        }
        assertFalse(referenced.isEmpty(), "the console templates read the admin model");
        assertTrue(beanMethodNames().containsAll(referenced), "templates reference unknown beans: " + referenced);
    }

    @Test
    void theDescriptorDeclaresTheSpiFloorAndTheReleaseVersion() throws IOException {
        String yml = Files.readString(Path.of("plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("\nrequiresSpi: \">=1.0.0\"\n"), "built for the frozen SPI 1.0.0 contract");
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("<artifactId>runway-plugin-carousel-manager</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(version.find());
        assertTrue(yml.contains("\nversion: " + version.group(1) + "\n"), "plugin.yml and pom.xml agree on the version");
        assertFalse(pom.contains("<artifactId>runway-app</artifactId>"), "no runway-app dependency in any scope");
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
        assertTrue(scanned >= 4, "the compiled classes were found (" + scanned + ")");
        assertTrue(problems.isEmpty(), "host linkage: " + problems);
    }
}
