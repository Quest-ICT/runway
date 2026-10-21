package mx.quest.runway.plugin;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Builds a <b>real</b> plugin jar at test time — the keystone for loader tests, which must never rely on
 * {@code spring-boot:run} (the DevTools restart classloader starts zero plugins). It compiles tiny plugin
 * sources with the in-JVM {@code javac} (against this test's own classpath, so plugin code can reference the
 * kernel/SPI), jars the classes plus a {@code plugin.yml} into {@code <root>/<id>/}, and the production loader
 * path loads it exactly as the packaged jar would. Fixture plugin classes live <b>outside</b> {@code mx.quest.runway}
 * to mirror the no-double-scan rule. Guard callers with {@link #compilerAvailable()} — the test server is JRE-only.
 */
public final class PluginFixture {

    private PluginFixture() {
    }

    /** The marker the {@code alpha} fixture's HeadContributor renders, so tests can assert it was wired. */
    static final String ALPHA_HEAD_MARKER = "<!--alpha-plugin-->";

    private static final String ALPHA_HEAD_SRC = """
            package fixtures.plugin.alpha;
            import mx.quest.runway.spi.HeadContributor;
            import mx.quest.runway.spi.model.HeadContext;
            public class AlphaHead implements HeadContributor {
                public String key() { return "alpha.head"; }
                public String render(HeadContext context) { return "%s"; }
            }
            """.formatted(ALPHA_HEAD_MARKER);

    private static final String ALPHA_CONFIG_SRC = """
            package fixtures.plugin.alpha;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class AlphaConfig {
                @Bean public AlphaHead alphaHead() { return new AlphaHead(); }
            }
            """;

    private static final String INERT_PROTECTION_CONFIG_SRC = """
            package fixtures.plugin.protection;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class ProtectionConfig {
                // an allowlisted-essential plugin that LOADS but contributes NO @Primary gate override —
                // the dangerous silent-open case the kernel must catch and fail closed.
            }
            """;

    /** Whether an in-JVM Java compiler is present (false on a JRE-only runtime — skip the test then). */
    public static boolean compilerAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    /** Builds an allowlisted-essential {@code protection} plugin that loads but does NOT arm the security floor. */
    static void buildInertProtection(Path pluginsRoot) throws IOException {
        build(pluginsRoot, "protection",
                "id: protection\nversion: 1.0.0\nessential: true\nrequiresSpi: \">=1.0.0\"\n"
                        + "mainClass: fixtures.plugin.protection.ProtectionConfig\n",
                Map.of("fixtures.plugin.protection.ProtectionConfig", INERT_PROTECTION_CONFIG_SRC));
    }

    private static final String ARMING_CONFIG_SRC = """
            package fixtures.plugin.protection;
            import mx.quest.runway.protect.EdgeGuard;
            import mx.quest.runway.protect.EdgeVerdict;
            import mx.quest.runway.security.LoginAttemptGate;
            import mx.quest.runway.security.LoginFormGuard;
            import mx.quest.runway.security.LoginFormVerdict;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.context.annotation.Primary;
            @Configuration
            public class ArmingConfig {
                @Bean @Primary public EdgeGuard armingEdgeGuard() { return r -> EdgeVerdict.pass(); }
                @Bean @Primary public LoginAttemptGate armingLoginGate() {
                    return new LoginAttemptGate() {
                        public boolean isLocked(String ip) { return false; }
                        public void recordFailure(String ip) { }
                        public void recordSuccess(String ip) { }
                    };
                }
                @Bean @Primary public LoginFormGuard armingFormGuard() {
                    return (request, scope) -> LoginFormVerdict.ok();
                }
            }
            """;

    /**
     * Writes a {@code protection} folder whose descriptor is present-but-unloadable (here: disabled) — no jar,
     * no compiler needed. The kernel must still fail closed (the folder is physically installed). Models the
     * malformed/disabled/blocked essential-plugin cases uniformly.
     */
    static void writeDisabledProtection(Path pluginsRoot) throws IOException {
        Path dir = Files.createDirectories(pluginsRoot.resolve("protection"));
        Files.writeString(dir.resolve("plugin.yml"),
                "id: protection\nversion: 1.0.0\nessential: true\nenabled: false\n", StandardCharsets.UTF_8);
    }

    /** Builds an allowlisted-essential {@code protection} plugin whose @Primary gates DO arm the security floor. */
    static void buildArmingProtection(Path pluginsRoot) throws IOException {
        build(pluginsRoot, "protection",
                "id: protection\nversion: 1.0.0\nessential: true\nrequiresSpi: \">=1.0.0\"\n"
                        + "mainClass: fixtures.plugin.protection.ArmingConfig\n",
                Map.of("fixtures.plugin.protection.ArmingConfig", ARMING_CONFIG_SRC));
    }

    /** The body the {@code alpha} fixture ships at {@code static/hello.txt}. */
    static final String ALPHA_STATIC_BODY = "hello plugin";

    /** Builds the {@code alpha} fixture plugin: a {@code @Configuration} contributing one {@code HeadContributor}. */
    static void buildAlpha(Path pluginsRoot) throws IOException {
        build(pluginsRoot, "alpha",
                "id: alpha\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: fixtures.plugin.alpha.AlphaConfig\n",
                Map.of("fixtures.plugin.alpha.AlphaHead", ALPHA_HEAD_SRC,
                        "fixtures.plugin.alpha.AlphaConfig", ALPHA_CONFIG_SRC));
        Path staticDir = Files.createDirectories(pluginsRoot.resolve("alpha").resolve("static"));
        Files.writeString(staticDir.resolve("hello.txt"), ALPHA_STATIC_BODY, StandardCharsets.UTF_8);
    }

    /**
     * Compiles {@code sources} (fully-qualified class name → source) and writes
     * {@code <pluginsRoot>/<id>/<id>.jar} + {@code <pluginsRoot>/<id>/plugin.yml}.
     */
    public static void build(Path pluginsRoot, String id, String pluginYml, Map<String, String> sources)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler (JRE-only) — guard with compilerAvailable()");
        }
        Path work = Files.createTempDirectory("runway-plugin-fixture-" + id);
        Path srcDir = Files.createDirectories(work.resolve("src"));
        Path classesDir = Files.createDirectories(work.resolve("classes"));

        List<Path> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path src = srcDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, entry.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(src);
        }

        StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString());
            boolean ok = compiler.getTask(diagnostics, fm, null, options, null, units).call();
            if (!ok) {
                throw new IllegalStateException("fixture compile failed:\n" + diagnostics);
            }
        }

        Path pluginDir = Files.createDirectories(pluginsRoot.resolve(id));
        jar(classesDir, pluginDir.resolve(id + ".jar"));
        Files.writeString(pluginDir.resolve("plugin.yml"), pluginYml, StandardCharsets.UTF_8);
    }

    private static void jar(Path classesDir, Path jarFile) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarFile));
                Stream<Path> walk = Files.walk(classesDir)) {
            List<Path> entries = walk.filter(p -> !p.equals(classesDir)).sorted().toList();
            for (Path dir : entries.stream().filter(Files::isDirectory).toList()) {
                // Directory entries, as a Maven-built jar has them: Spring's classpath scanning (@ComponentScan) finds
                // a package in a jar only through its directory entry.
                jos.putNextEntry(new JarEntry(classesDir.relativize(dir).toString().replace(File.separatorChar, '/')
                        + "/"));
                jos.closeEntry();
            }
            List<Path> classFiles = entries.stream().filter(Files::isRegularFile).toList();
            for (Path classFile : classFiles) {
                String entryName = classesDir.relativize(classFile).toString().replace(File.separatorChar, '/');
                jos.putNextEntry(new JarEntry(entryName));
                Files.copy(classFile, jos);
                jos.closeEntry();
            }
        }
    }
}
