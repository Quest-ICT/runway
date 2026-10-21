package mx.quest.runway.plugin;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the <b>constant pool</b> of every class on a plugin's class path — its top-level jars and {@code lib/*.jar},
 * exactly the jars {@link PluginLoader#classLoaderFor} adds — without loading a single class —
 * and reports how the plugin links the host. Every finding <b>blocks</b> the plugin before
 * wiring (SPI 1.0.0; until rc1 the first and third only warned):
 *
 * <ol>
 *   <li><b>Host linkage</b>: a class reference, descriptor or signature naming {@code mx/quest/runway/…} outside
 *       {@code mx/quest/runway/spi/}. The 1.0 contract is the SPI alone; a plugin that links a kernel class would
 *       break on any kernel release that changes it, so it is refused at startup instead of failing later on a
 *       request.</li>
 *   <li><b>Missing SPI types</b>: an {@code mx/quest/runway/spi/…} type the running kernel does not have — a plugin
 *       built against a newer SPI that forgot (or misdeclared) its {@code requiresSpi} floor (a second guard
 *       for a missing or wrong floor): such a plugin cannot work, and refusing it at startup with a readable
 *       reason beats a {@code NoClassDefFoundError} on some later public request.</li>
 *   <li><b>A class living under {@code mx.quest.runway}</b>: the host component-scans that package, and the
 *       class's linkage would be indistinguishable from the host's own.</li>
 * </ol>
 *
 * The essential plugin is never scanned (it ships with the kernel and may link the security-floor seams); the
 * caller skips it.
 *
 * <h2>What is read</h2>
 * Class entries ({@code CONSTANT_Class}) and every {@code CONSTANT_Utf8} that can hold a descriptor or a generic
 * signature — field and method descriptors, annotation types, {@code invokedynamic} types — matched as
 * {@code L<name>;} or {@code L<name><}. A {@code CONSTANT_Utf8} used only as a string literal is skipped, so a
 * plugin that merely <em>mentions</em> a host class name in a message is not flagged. {@code lib/} jars are scanned
 * like the top-level ones (since 1.0.0-rc1): they are on the same class path, so a host-linking helper
 * moved into {@code lib/} would otherwise pass the contract check. A third-party library links nothing of ours and
 * passes; scanning it costs one read per jar per boot.
 *
 * <h2>What cannot be read blocks (fail closed)</h2>
 * A jar that cannot be opened, or a {@code .class} entry whose constant pool cannot be parsed, is a class the
 * scanner cannot vouch for — a fourth finding that <b>blocks</b>, naming the jar or the entry
 * ({@code lib/x.jar!a/B.class}); the other entries of the jar are still scanned (one bogus entry
 * used to make the whole jar "unreadable", which never blocked, so its real classes went unchecked). The one exception
 * is an entry the JVM can never load here: a multi-release entry {@code META-INF/versions/<n>/…} with {@code n} above
 * the running Java feature version — the class loader opens jars at the runtime version and never serves it.
 * ({@code module-info.class} entries are skipped as before: no class loader defines them.) Every other entry is
 * loadable by name; the parser reads every constant-pool tag the running JVM defines, so an entry it rejects is most
 * likely malformed — but "the JVM would refuse it too" is the parser's claim, not a proof, so it blocks.
 *
 * <p>Reports are memoised per jar (path, size, modification time), so the plugins screen can re-ask cheaply.
 */
public final class HostLinkageScanner {

    private static final Logger log = LoggerFactory.getLogger(HostLinkageScanner.class);

    private static final String HOST = "mx/quest/runway/";
    private static final String SPI = "mx/quest/runway/spi/";
    private static final Pattern DESCRIPTOR_REF = Pattern.compile("L(mx/quest/runway/[^;<>\\[]+)[;<]");
    private static final int MAX_LISTED = 12;

    /**
     * What one plugin's jars link. All names are binary names with dots ({@code mx.quest.runway.x.Y}).
     *
     * @param unreadable jars that could not be opened and class entries that could not be parsed, each as
     *                   {@code <jar, relative to the plugin folder>[!<entry>]: <reason>} — every one blocks
     */
    public record Report(Set<String> hostTypes, Set<String> missingSpiTypes, Set<String> classesUnderRunway,
                         List<String> unreadable) {

        public Report {
            hostTypes = Set.copyOf(hostTypes);
            missingSpiTypes = Set.copyOf(missingSpiTypes);
            classesUnderRunway = Set.copyOf(classesUnderRunway);
            unreadable = List.copyOf(unreadable);
        }

        static final Report EMPTY = new Report(Set.of(), Set.of(), Set.of(), List.of());

        /** Whether the plugin links host-internal classes or lives in the host's package. */
        public boolean linksHost() {
            return !hostTypes.isEmpty() || !classesUnderRunway.isEmpty();
        }

        /**
         * The operator-readable reason the plugin is blocked, or {@code null} when nothing was found: SPI types this
         * kernel lacks (the plugin is too new), host linkage (built against kernel internals), classes in the
         * kernel's package, class files that could not be read — every finding present, in that order, in one line.
         */
        public String blockReason() {
            List<String> parts = new ArrayList<>();
            if (!missingSpiTypes.isEmpty()) {
                parts.add("needs SPI types this Runway does not have: " + list(missingSpiTypes)
                        + ". It was built for a newer Runway — upgrade Runway, or install an earlier version of the "
                        + "plugin");
            }
            if (linksHost()) {
                StringBuilder text = new StringBuilder("uses Runway's internal classes, which the plugin contract "
                        + "(SPI 1.0) does not allow");
                if (!hostTypes.isEmpty()) {
                    text.append(" (links ").append(hostTypes.size()).append(" kernel class")
                            .append(hostTypes.size() == 1 ? "" : "es").append(": ").append(list(hostTypes))
                            .append(')');
                }
                if (!classesUnderRunway.isEmpty()) {
                    text.append(" (classes in the kernel's package mx.quest.runway: ")
                            .append(list(classesUnderRunway)).append(')');
                }
                text.append(". Install the release of this plugin built against the SPI only");
                parts.add(text.toString());
            }
            if (!unreadable.isEmpty()) {
                List<String> shown = unreadable.subList(0, Math.min(MAX_LISTED, unreadable.size()));
                parts.add("contains class files Runway could not read, so what they link cannot be verified: "
                        + String.join("; ", shown)
                        + (unreadable.size() > MAX_LISTED ? "; … (" + (unreadable.size() - MAX_LISTED) + " more)" : "")
                        + ". The plugin package is damaged or was not built by a Java compiler — re-download or "
                        + "rebuild it");
            }
            return parts.isEmpty() ? null : String.join("; it also ", parts) + ".";
        }

        private static String list(Set<String> names) {
            List<String> sorted = new ArrayList<>(new TreeSet<>(names));
            String shown = String.join(", ", sorted.subList(0, Math.min(MAX_LISTED, sorted.size())));
            return sorted.size() > MAX_LISTED ? shown + ", … (" + (sorted.size() - MAX_LISTED) + " more)" : shown;
        }
    }

    private record JarKey(Path path, long size, FileTime modified) {
    }

    private record JarScan(Set<String> referenced, Set<String> ownClasses, List<String> unreadableEntries) {
    }

    private static final Map<JarKey, JarScan> CACHE = new ConcurrentHashMap<>();

    private HostLinkageScanner() {
    }

    /**
     * Scans every jar on {@code plugin}'s class path: {@code <installDir>/*.jar}, then {@code <installDir>/lib/*.jar}.
     *
     * @param spiTypeExists whether the running kernel has an SPI type (binary name with slashes, e.g.
     *                      {@code mx/quest/runway/spi/model/PageMeta})
     */
    public static Report scan(LoadedPlugin plugin, Predicate<String> spiTypeExists) {
        Set<String> host = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        Set<String> under = new TreeSet<>();
        List<String> unreadable = new ArrayList<>();
        Set<String> ownAll = new TreeSet<>();
        for (Path jar : classPathJars(plugin.installDir())) {
            String shown = plugin.installDir().relativize(jar).toString().replace('\\', '/');
            JarScan scan;
            try {
                scan = scanJar(jar);
            } catch (IOException | RuntimeException failure) {
                unreadable.add(shown + ": " + failure);
                log.warn("could not scan plugin '{}' jar {} for host linkage: {}", plugin.id(), jar, failure.toString());
                continue;
            }
            for (String entry : scan.unreadableEntries()) {
                unreadable.add(shown + "!" + entry);
            }
            ownAll.addAll(scan.ownClasses());
            for (String name : scan.referenced()) {
                if (name.startsWith(SPI)) {
                    if (!spiTypeExists.test(name)) {
                        missing.add(name.replace('/', '.'));
                    }
                } else {
                    host.add(name.replace('/', '.'));
                }
            }
            for (String own : scan.ownClasses()) {
                if (own.startsWith(HOST)) {
                    under.add(own.replace('/', '.'));
                }
            }
        }
        // A class another of the plugin's own jars defines is not kernel linkage (it is reported under
        // classesUnderRunway when it lives in the kernel's package).
        ownAll.forEach(own -> host.remove(own.replace('/', '.')));
        return host.isEmpty() && missing.isEmpty() && under.isEmpty() && unreadable.isEmpty() ? Report.EMPTY
                : new Report(host, missing, under, unreadable);
    }

    /** The running kernel's SPI, as seen by {@code loader} (the application class loader). */
    public static Predicate<String> spiOf(ClassLoader loader) {
        return internalName -> loader.getResource(internalName + ".class") != null;
    }

    /** The plugin's top-level jars, then its {@code lib/} jars — the loader's class path for it. */
    private static List<Path> classPathJars(Path installDir) {
        if (installDir == null || !Files.isDirectory(installDir)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>(jarsIn(installDir));
        jars.addAll(jarsIn(installDir.resolve("lib")));
        return jars;
    }

    private static final Pattern VERSIONED = Pattern.compile("META-INF/versions/(\\d{1,9})/.*");

    /**
     * Whether the JVM can never load this jar entry here: a multi-release entry for a Java version above the running
     * one (the class loader opens the jar at the runtime version and never serves it).
     */
    static boolean neverLoadedHere(String entryName) {
        Matcher m = VERSIONED.matcher(entryName);
        return m.matches() && Integer.parseInt(m.group(1)) > Runtime.version().feature();
    }

    private static List<Path> jarsIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".jar")).filter(Files::isRegularFile)
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static JarScan scanJar(Path jar) throws IOException {
        JarKey key = new JarKey(jar.toAbsolutePath().normalize(), Files.size(jar), Files.getLastModifiedTime(jar));
        JarScan cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Set<String> referenced = new TreeSet<>();
        Set<String> own = new TreeSet<>();
        List<String> unreadableEntries = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")
                        || neverLoadedHere(name)) {
                    continue;
                }
                // Parse into scratch sets: a class that fails half-way contributes nothing but its name.
                Set<String> classReferenced = new TreeSet<>();
                Set<String> classOwn = new TreeSet<>();
                try (InputStream in = file.getInputStream(entry)) {
                    readClass(new DataInputStream(in), classReferenced, classOwn);
                } catch (IOException | RuntimeException unreadable) {
                    unreadableEntries.add(name + ": " + unreadable.getMessage());
                    continue;
                }
                referenced.addAll(classReferenced);
                own.addAll(classOwn);
            }
        }
        referenced.removeAll(own); // the jar's own classes are not host linkage, wherever they live
        JarScan scan = new JarScan(Set.copyOf(referenced), Set.copyOf(own), List.copyOf(unreadableEntries));
        CACHE.put(key, scan);
        return scan;
    }

    /**
     * Parses one class file's constant pool (JVMS §4.4) and this-class name. Only references naming
     * {@code mx/quest/runway/} are kept.
     */
    static void readClass(DataInputStream in, Set<String> referenced, Set<String> own) throws IOException {
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        BitSet literal = new BitSet(count);
        BitSet descriptor = new BitSet(count);
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++; // a long or double occupies two slots
                }
                case 7 -> classNameIndex[i] = in.readUnsignedShort();
                case 8 -> literal.set(in.readUnsignedShort());
                case 9, 10, 11, 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                case 12 -> {
                    in.readUnsignedShort();
                    descriptor.set(in.readUnsignedShort());
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 16 -> descriptor.set(in.readUnsignedShort());
                case 19, 20 -> in.readUnsignedShort();
                default -> throw new IOException("unknown constant-pool tag " + tag + " at " + i);
            }
        }
        in.readUnsignedShort(); // access flags
        int thisClass = in.readUnsignedShort();
        BitSet classNames = new BitSet(count);
        for (int i = 1; i < count; i++) {
            if (classNameIndex[i] > 0) {
                classNames.set(classNameIndex[i]);
                String name = utf8[classNameIndex[i]];
                if (name != null) {
                    addClassName(name, referenced);
                }
            }
        }
        if (thisClass > 0 && thisClass < count && classNameIndex[thisClass] > 0) {
            String self = utf8[classNameIndex[thisClass]];
            if (self != null) {
                own.add(self);
            }
        }
        for (int i = 1; i < count; i++) {
            String text = utf8[i];
            if (text == null || classNames.get(i) || (literal.get(i) && !descriptor.get(i))
                    || text.indexOf(HOST) < 0) {
                continue;
            }
            Matcher m = DESCRIPTOR_REF.matcher(text);
            while (m.find()) {
                referenced.add(m.group(1));
            }
        }
    }

    private static void addClassName(String name, Set<String> referenced) {
        if (name.startsWith("[")) {
            Matcher m = DESCRIPTOR_REF.matcher(name);
            while (m.find()) {
                referenced.add(m.group(1));
            }
        } else if (name.startsWith(HOST)) {
            referenced.add(name);
        }
    }
}
