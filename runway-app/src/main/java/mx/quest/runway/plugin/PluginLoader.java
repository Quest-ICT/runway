package mx.quest.runway.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The filesystem half of the loader: <b>discovery</b> (scan each {@code external/plugins/<id>/plugin.yml}, parse,
 * skip the bad) and <b>ordering</b> (topo-sort the loadable set so a provider's {@code @Configuration} registers
 * before its consumers'). Resolution ({@code requires:}/{@code conflicts:}) is {@link PluginResolution}, a
 * separate class the admin UI can call standalone to preview the next boot's Blocked set. The shared
 * classloader is built by {@code PluginContextInitializer}.
 */
public final class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);
    private static final String DESCRIPTOR = "plugin.yml";

    private PluginLoader() {
    }

    /**
     * Discovers the enabled, valid plugins under {@code root} (each immediate subdir holding a {@code plugin.yml}),
     * in id order. Defensive — every reject is one log line, never an abort: no {@code plugin.yml} skips silently;
     * an unreadable/invalid descriptor warns and skips; {@code enabled: false} skips; a duplicate id is
     * <b>first-wins</b> (deterministic via the sorted scan).
     */
    public static List<LoadedPlugin> discover(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> dirs;
        try (Stream<Path> entries = Files.list(root)) {
            dirs = entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing plugins under " + root, e);
        }
        Map<String, LoadedPlugin> byId = new LinkedHashMap<>();
        for (Path dir : dirs) {
            Path descriptorFile = dir.resolve(DESCRIPTOR);
            if (!Files.isRegularFile(descriptorFile)) {
                continue; // not a plugin directory
            }
            LoadedPlugin loaded = tryLoad(dir, descriptorFile);
            if (loaded != null && byId.putIfAbsent(loaded.id(), loaded) != null) {
                log.warn("duplicate plugin id '{}' at {} — ignoring this one (first-wins)", loaded.id(), dir);
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * The ids (directory names) of plugins <b>physically present</b> under {@code root} — every immediate subdir
     * holding a {@code plugin.yml}, <b>regardless</b> of whether it parses, validates, or is enabled. This is the
     * fail-closed signal: a security-floor plugin's folder being present-but-unloadable (malformed/disabled/
     * blocked/threw) must trip degraded mode, which the filtered discovery list cannot see.
     */
    public static Set<String> installedPluginIds(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Set.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(DESCRIPTOR)))
                    .map(dir -> dir.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("listing plugin folders under " + root, e);
        }
    }

    private static LoadedPlugin tryLoad(Path dir, Path descriptorFile) {
        PluginDescriptor descriptor;
        try (InputStream in = Files.newInputStream(descriptorFile)) {
            descriptor = PluginDescriptor.read(in);
        } catch (Exception e) {
            log.warn("skipping plugin at {}: unreadable plugin.yml ({})", dir, e.getMessage());
            return null;
        }
        try {
            descriptor.validate();
        } catch (RuntimeException e) {
            log.warn("skipping plugin at {}: invalid descriptor ({})", dir, e.getMessage());
            return null;
        }
        if (!descriptor.isEnabled()) {
            log.info("plugin '{}' is disabled — skipping", descriptor.id());
            return null;
        }
        String dirName = dir.getFileName().toString();
        if (!descriptor.id().equals(dirName)) {
            log.warn("skipping plugin at {}: descriptor id '{}' does not match its directory name '{}'",
                    dir, descriptor.id(), dirName);
            return null;
        }
        return new LoadedPlugin(descriptor, dir);
    }

    /**
     * Builds <b>one</b> shared {@link URLClassLoader} over every loadable plugin's {@code *.jar} and {@code lib/*.jar},
     * with {@code parent} = the application classloader (<b>parent-first</b>). Parent-first is the keystone: an SPI
     * type resolves to the <em>same</em> {@code Class} the kernel uses, so a plugin's {@code HeadContributor} (etc.)
     * satisfies the kernel's {@code List<HeadContributor>} injection by type — no {@code instanceof} mismatch.
     * One classloader also means one version of any library deployment-wide (hence the library-provider convention).
     */
    public static ClassLoader classLoaderFor(List<LoadedPlugin> loadable, ClassLoader parent) {
        List<URL> urls = new ArrayList<>();
        // basename -> first path seen, so a library jar bundled by two plugins is flagged. One shared classloader
        // means one version of any library deployment-wide; a duplicate silently shadows and can throw
        // ClassCastException/NoClassDefFoundError at runtime (the library-provider convention avoids this).
        Map<String, Path> seenJars = new LinkedHashMap<>();
        for (LoadedPlugin plugin : loadable) {
            collectJars(plugin.installDir(), urls, seenJars);
            collectJars(plugin.installDir().resolve("lib"), urls, seenJars);
        }
        if (urls.isEmpty()) {
            return parent; // no jars (e.g. all library-only with no jars) — nothing to add
        }
        return new URLClassLoader("runway-plugins", urls.toArray(URL[]::new), parent);
    }

    private static void collectJars(Path dir, List<URL> urls, Map<String, Path> seenJars) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(jar -> {
                        Path prior = seenJars.putIfAbsent(jar.getFileName().toString(), jar);
                        if (prior != null) {
                            log.warn("two plugins bundle the library jar '{}' ({} and {}) — the shared classloader "
                                    + "keeps only one; move it into a library-provider plugin to avoid a silent "
                                    + "version clash", jar.getFileName(), prior, jar);
                        }
                        urls.add(toUrl(jar));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("listing jars under " + dir, e);
        }
    }

    private static URL toUrl(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("bad jar URL: " + jar, e);
        }
    }

    /**
     * Topologically orders the (already-resolved) loadable set so each plugin follows the plugins it
     * {@code requires:} — a provider's {@code @Configuration} registers before its consumers'. Cycles are
     * impossible past resolution; if one is somehow present it is logged and the discovery order is kept.
     */
    public static List<LoadedPlugin> order(List<LoadedPlugin> loadable) {
        Map<String, LoadedPlugin> byId = new LinkedHashMap<>();
        for (LoadedPlugin plugin : loadable) {
            byId.put(plugin.id(), plugin);
        }
        List<LoadedPlugin> ordered = new ArrayList<>(loadable.size());
        Set<String> done = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        boolean[] cycle = {false};
        for (LoadedPlugin plugin : loadable) {
            visit(plugin, byId, done, onPath, ordered, cycle);
        }
        if (cycle[0]) {
            log.warn("requires: cycle among loadable plugins — keeping discovery order");
            return loadable;
        }
        return List.copyOf(ordered);
    }

    private static void visit(LoadedPlugin plugin, Map<String, LoadedPlugin> byId, Set<String> done,
                              Set<String> onPath, List<LoadedPlugin> out, boolean[] cycle) {
        if (done.contains(plugin.id())) {
            return;
        }
        if (!onPath.add(plugin.id())) {
            cycle[0] = true;
            return;
        }
        for (PluginRequirement requirement : plugin.descriptor().requirements()) {
            LoadedPlugin dependency = byId.get(requirement.id());
            if (dependency != null) {
                visit(dependency, byId, done, onPath, out, cycle);
            }
        }
        onPath.remove(plugin.id());
        if (done.add(plugin.id())) {
            out.add(plugin);
        }
    }
}
