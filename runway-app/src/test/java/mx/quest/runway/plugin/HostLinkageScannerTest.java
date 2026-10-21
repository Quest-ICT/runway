package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host-linkage scanner on fixture jars: a host import, a host type only in a method descriptor, a host annotation, a
 * string that merely names a host class (not linkage), a missing SPI type, and a class living under
 * {@code mx.quest.runway}.
 */
class HostLinkageScannerTest {

    @TempDir
    Path root;

    private static final String HOST_IMPORT = """
            package fixtures.scan;
            import mx.quest.runway.site.SiteStore;
            public class UsesStore {
                private SiteStore store;
                public Object get() { return store; }
            }
            """;

    private static final String HOST_IN_DESCRIPTOR_ONLY = """
            package fixtures.scan;
            public class DescriptorOnly {
                public void accept(mx.quest.runway.site.Site site) { }
            }
            """;

    private static final String HOST_ANNOTATION = """
            package fixtures.scan;
            @mx.quest.runway.scanfixture.HostMarker
            public class Annotated { }
            """;

    private static final String HOST_ANNOTATION_TYPE = """
            package mx.quest.runway.scanfixture;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface HostMarker { }
            """;

    private static final String ONLY_STRINGS = """
            package fixtures.scan;
            public class Mentions {
                public static final String A = "mx/quest/runway/site/SiteStore";
                public static final String B = "Lmx/quest/runway/site/Site;";
                public String hint() { return "see mx.quest.runway.site.SiteResolver"; }
            }
            """;

    private static final String SPI_USER = """
            package fixtures.scan;
            public class SpiUser implements mx.quest.runway.spi.HeadContributor {
                public String key() { return "k"; }
                public String render(mx.quest.runway.spi.model.HeadContext c) {
                    return mx.quest.runway.spi.FutureApi.hello();
                }
            }
            """;

    private static final String FUTURE_SPI = """
            package mx.quest.runway.spi;
            public final class FutureApi { public static String hello() { return "hi"; } }
            """;

    private static final String CLEAN = """
            package fixtures.scan;
            public class Clean implements mx.quest.runway.spi.ContentWidget {
                public String key() { return "w"; }
                public mx.quest.runway.spi.model.RenderFragment render(mx.quest.runway.spi.model.WidgetView v) {
                    return mx.quest.runway.spi.model.RenderFragment.empty();
                }
            }
            """;

    private LoadedPlugin plugin(String id, Map<String, String> sources, String... dropFromJar) throws IOException {
        PluginFixture.build(root, id, "id: " + id + "\nversion: 1.0.0\n", sources);
        Path jar = root.resolve(id).resolve(id + ".jar");
        if (dropFromJar.length > 0) {
            rejarWithout(jar, List.of(dropFromJar));
        }
        PluginDescriptor d = PluginDescriptor.read(new ByteArrayInputStream(
                ("id: " + id + "\nversion: 1.0.0\n").getBytes(StandardCharsets.UTF_8)));
        return new LoadedPlugin(d, root.resolve(id));
    }

    /** Rewrites {@code jar} without the entries whose names start with any of {@code prefixes}. */
    private static void rejarWithout(Path jar, List<String> prefixes) throws IOException {
        Map<String, byte[]> kept = new LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (prefixes.stream().noneMatch(entry.getName()::startsWith)) {
                    try (InputStream in = file.getInputStream(entry)) {
                        kept.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : kept.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static HostLinkageScanner.Report scan(LoadedPlugin plugin) {
        return HostLinkageScanner.scan(plugin, HostLinkageScanner.spiOf(HostLinkageScannerTest.class.getClassLoader()));
    }

    @Test
    void aPluginThatOnlyUsesTheSpiIsClean() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        HostLinkageScanner.Report report = scan(plugin("clean", Map.of("fixtures.scan.Clean", CLEAN)));
        assertFalse(report.linksHost());
        assertNull(report.blockReason());
    }

    @Test
    void hostImportsDescriptorsAndAnnotationsAreFound() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        HostLinkageScanner.Report report = scan(plugin("hosty", Map.of(
                "fixtures.scan.UsesStore", HOST_IMPORT,
                "fixtures.scan.DescriptorOnly", HOST_IN_DESCRIPTOR_ONLY,
                "fixtures.scan.Annotated", HOST_ANNOTATION,
                "mx.quest.runway.scanfixture.HostMarker", HOST_ANNOTATION_TYPE), "mx/quest/runway/scanfixture/"));
        assertTrue(report.hostTypes().contains("mx.quest.runway.site.SiteStore"), report.toString());
        assertTrue(report.hostTypes().contains("mx.quest.runway.site.Site"), "a descriptor-only reference counts");
        assertTrue(report.hostTypes().contains("mx.quest.runway.scanfixture.HostMarker"), "an annotation counts");
        assertTrue(report.linksHost());
        assertTrue(report.blockReason().startsWith("uses Runway's internal classes, which the plugin contract (SPI "
                + "1.0) does not allow (links "), "host linkage blocks from SPI 1.0: " + report.blockReason());
        assertTrue(report.blockReason().contains("mx.quest.runway.site.SiteStore"), report.blockReason());
        assertTrue(report.blockReason().endsWith("Install the release of this plugin built against the SPI only."),
                report.blockReason());
    }

    @Test
    void aPluginThatIsBothTooNewAndHostLinkedGivesBothReasonsInOneLine() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        HostLinkageScanner.Report report = scan(plugin("both", Map.of("fixtures.scan.SpiUser", SPI_USER,
                "mx.quest.runway.spi.FutureApi", FUTURE_SPI, "fixtures.scan.UsesStore", HOST_IMPORT),
                "mx/quest/runway/spi/"));
        String reason = report.blockReason();
        assertTrue(reason.startsWith("needs SPI types this Runway does not have"), reason);
        assertTrue(reason.contains("; it also uses Runway's internal classes"), reason);
        assertFalse(reason.contains("\n"), "one line");
    }

    @Test
    void aStringThatMerelyNamesAHostClassIsNotLinkage() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        HostLinkageScanner.Report report = scan(plugin("talky", Map.of("fixtures.scan.Mentions", ONLY_STRINGS)));
        assertFalse(report.linksHost(), report.toString());
    }

    @Test
    void aMissingSpiTypeBlocksWithAReadableReason() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        // Compiled against a "newer SPI" that has FutureApi, then shipped without it — exactly a newer plugin on an
        // older kernel.
        HostLinkageScanner.Report report = scan(plugin("future", Map.of("fixtures.scan.SpiUser", SPI_USER,
                "mx.quest.runway.spi.FutureApi", FUTURE_SPI), "mx/quest/runway/spi/"));
        assertEquals(java.util.Set.of("mx.quest.runway.spi.FutureApi"), report.missingSpiTypes());
        assertTrue(report.blockReason().contains("needs SPI types this Runway does not have"), report.blockReason());
        assertTrue(report.blockReason().contains("mx.quest.runway.spi.FutureApi"));
        assertFalse(report.missingSpiTypes().contains("mx.quest.runway.spi.HeadContributor"), "present types pass");
    }

    @Test
    void aClassLivingUnderTheKernelPackageIsReported() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        HostLinkageScanner.Report report = scan(plugin("squatter", Map.of(
                "mx.quest.runway.scanfixture.HostMarker", HOST_ANNOTATION_TYPE)));
        assertEquals(java.util.Set.of("mx.quest.runway.scanfixture.HostMarker"), report.classesUnderRunway());
        assertTrue(report.hostTypes().isEmpty(), "its own class is not host linkage");
        assertTrue(report.linksHost(), "living in the kernel's package counts as host linkage");
        assertTrue(report.blockReason().contains("classes in the kernel's package mx.quest.runway: "
                + "mx.quest.runway.scanfixture.HostMarker"), report.blockReason());
    }

    /**
     * A jar the scanner cannot open is on the plugin class path all the same (the loader adds
     * every {@code *.jar}), so what it links cannot be verified — it blocks, naming the jar (fail closed; it used to be
     * listed and never block).
     */
    @Test
    void anUnreadableJarBlocksNamingIt() throws IOException {
        Path dir = Files.createDirectories(root.resolve("broken"));
        Files.writeString(dir.resolve("broken.jar"), "this is not a zip");
        PluginDescriptor d = PluginDescriptor.read(new ByteArrayInputStream(
                "id: broken\nversion: 1.0.0\n".getBytes(StandardCharsets.UTF_8)));
        HostLinkageScanner.Report report = scan(new LoadedPlugin(d, dir));
        assertEquals(1, report.unreadable().size());
        assertFalse(report.linksHost(), "unverified is not the same as host-linked (the screen's badge)");
        assertNotNull(report.blockReason(), "an unreadable jar blocks");
        assertTrue(report.blockReason().contains("broken.jar"), report.blockReason());
    }

    /** A class file's bytes: magic, version 65, a constant pool whose first tag is {@code tag}. */
    private static byte[] classFile(int tag) {
        return new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65, 0, 3, (byte) tag, 0, 0};
    }

    /** Adds raw entries to {@code jar}. */
    private static void addEntries(Path jar, Map<String, byte[]> extra) throws IOException {
        Map<String, byte[]> all = new LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                try (InputStream in = file.getInputStream(entry)) {
                    all.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        all.putAll(extra);
        writeJar(jar, all);
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    /**
     * One bogus {@code .class} entry used to make the WHOLE jar "unreadable", so its real classes
     * went unscanned and nothing blocked. Now the bad entry alone is skipped and named in the block reason, and every
     * other class of the jar is still scanned.
     */
    @Test
    void aBogusClassEntryBlocksNamingItAndTheRestIsStillScanned() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        LoadedPlugin hosty = plugin("bogus-hosty", Map.of("fixtures.scan.UsesStore", HOST_IMPORT));
        addEntries(hosty.installDir().resolve("bogus-hosty.jar"), Map.of("a/Bogus.class", classFile(99)));
        HostLinkageScanner.Report report = scan(hosty);
        assertTrue(report.hostTypes().contains("mx.quest.runway.site.SiteStore"), "the real classes are scanned");
        assertTrue(report.blockReason().contains("a/Bogus.class"), report.blockReason());

        LoadedPlugin clean = plugin("bogus-clean", Map.of("fixtures.scan.Clean", CLEAN));
        addEntries(clean.installDir().resolve("bogus-clean.jar"), Map.of("a/Bogus.class", "not a class".getBytes(
                StandardCharsets.UTF_8), "b/Truncated.class", java.util.Arrays.copyOf(classFile(1), 9)));
        HostLinkageScanner.Report cleanReport = scan(clean);
        assertFalse(cleanReport.linksHost());
        String reason = cleanReport.blockReason();
        assertNotNull(reason, "a clean plugin with an unverifiable class still blocks (fail closed)");
        assertTrue(reason.contains("bogus-clean.jar!a/Bogus.class") && reason.contains("b/Truncated.class"), reason);
    }

    /**
     * A multi-release entry for a Java version newer than the running one is never loaded on this runtime (the class
     * loader's jar is opened at the runtime version), so an entry there that cannot be read does not block; one for
     * the running version (or older) can be loaded, and blocks.
     */
    @Test
    void aVersionedEntryTheRuntimeNeverLoadsDoesNotBlock() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        int feature = Runtime.version().feature();
        LoadedPlugin future = plugin("mr-future", Map.of("fixtures.scan.Clean", CLEAN));
        addEntries(future.installDir().resolve("mr-future.jar"),
                Map.of("META-INF/versions/" + (feature + 1) + "/fixtures/scan/Clean.class", classFile(99)));
        assertNull(scan(future).blockReason(), "versions/" + (feature + 1) + " is not loadable on Java " + feature);

        LoadedPlugin current = plugin("mr-current", Map.of("fixtures.scan.Clean", CLEAN));
        addEntries(current.installDir().resolve("mr-current.jar"),
                Map.of("META-INF/versions/" + feature + "/fixtures/scan/Clean.class", classFile(99)));
        assertNotNull(scan(current).blockReason(), "versions/" + feature + " is loadable here");
    }

    /**
     * {@code lib/*.jar} is on the plugin class path ({@link PluginLoader#classLoaderFor}), so a
     * host-linking class there is host linkage like one in the top-level jar — it used to pass unscanned.
     */
    @Test
    void aHostLinkingClassInLibIsCaught() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        LoadedPlugin libby = plugin("libby", Map.of("fixtures.scan.Clean", CLEAN, "fixtures.scan.UsesStore",
                HOST_IMPORT));
        Path top = libby.installDir().resolve("libby.jar");
        Map<String, byte[]> moved = new LinkedHashMap<>();
        try (JarFile file = new JarFile(top.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (entry.getName().startsWith("fixtures/scan/UsesStore")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        moved.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        assertFalse(moved.isEmpty());
        rejarWithout(top, List.of("fixtures/scan/UsesStore"));
        writeJar(libby.installDir().resolve("lib/helper.jar"), moved);
        HostLinkageScanner.Report report = scan(libby);
        assertTrue(report.linksHost(), "the lib/ class links SiteStore");
        assertTrue(report.blockReason().contains("mx.quest.runway.site.SiteStore"), report.blockReason());

        // ...and a class under mx.quest.runway placed in lib/ is reported like one in the top-level jar.
        LoadedPlugin squat = plugin("libsquat", Map.of("fixtures.scan.Clean", CLEAN));
        LoadedPlugin donor = plugin("donor", Map.of("mx.quest.runway.scanfixture.HostMarker", HOST_ANNOTATION_TYPE));
        Files.createDirectories(squat.installDir().resolve("lib"));
        Files.copy(donor.installDir().resolve("donor.jar"), squat.installDir().resolve("lib/donor.jar"));
        assertEquals(java.util.Set.of("mx.quest.runway.scanfixture.HostMarker"), scan(squat).classesUnderRunway());
    }

    @Test
    void theInitializerBlocksMissingSpiAndHostLinkageButExemptsTheEssentialPlugin() throws IOException {
        assumeTrue(PluginFixture.compilerAvailable());
        LoadedPlugin future = plugin("future", Map.of("fixtures.scan.SpiUser", SPI_USER,
                "mx.quest.runway.spi.FutureApi", FUTURE_SPI), "mx/quest/runway/spi/");
        LoadedPlugin hosty = plugin("hosty", Map.of("fixtures.scan.UsesStore", HOST_IMPORT));
        LoadedPlugin clean = plugin("clean", Map.of("fixtures.scan.Clean", CLEAN));
        LoadedPlugin protection = plugin("protection", Map.of("fixtures.scan.UsesStore", HOST_IMPORT));
        Map<String, String> blocked = new LinkedHashMap<>();
        java.util.Set<String> linksHost = new java.util.HashSet<>();
        PluginContextInitializer.scanLinkage(new ArrayList<>(List.of(future, hosty, clean, protection)),
                getClass().getClassLoader(), blocked, linksHost, false);
        assertEquals(List.of("future", "hosty"), List.copyOf(blocked.keySet()), "SPI 1.0: host linkage blocks too");
        assertTrue(blocked.get("hosty").contains("mx.quest.runway.site.SiteStore"), blocked.toString());
        assertEquals(java.util.Set.of("hosty"), linksHost, "the badge set: host-linked, scanned plugins only");
        assertFalse(blocked.containsKey("protection"), "the essential plugin is exempt");
        assertFalse(blocked.containsKey("clean"));
    }
}
