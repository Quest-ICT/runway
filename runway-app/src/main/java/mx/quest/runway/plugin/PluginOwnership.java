package mx.quest.runway.plugin;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Answers "which plugin does this class belong to?" by <b>code source</b> — the jar the class was loaded from —
 * never by anything the class says about itself. It is the identity behind every plugin-scoped service
 * ({@code PluginSettings}, {@code PluginInfo}), behind matching a {@code PluginLifecycle} to its plugin, behind
 * the per-plugin failure counters, and behind deciding which side of a contribution collision is a plugin's.
 *
 * <h2>What counts</h2>
 * Only a plugin's <b>own top-level jars</b> — the {@code *.jar} files directly in its install folder. Classes from
 * its {@code lib/} jars do not belong to it: all plugins share one class loader, so a library jar bundled by two
 * plugins loads from whichever URL comes first, and attributing it to either would be a guess. Such a class is
 * reported as {@link Owner.Kind#LIBRARY} so callers can say so precisely. Jar locations are compared as <b>real
 * paths</b> (symlinks resolved), so a symlinked plugins folder cannot make one jar look like two.
 *
 * <p>A CGLIB subclass Spring generates for a plugin class ({@code @Configuration} enhancement) is attributed to its
 * user class. Kernel classes and test classes (no plugin jar) are {@link Owner.Kind#NONE}.
 *
 * <p>Thread-safe; the jar index is built once on first use (plugins cannot change without a restart) and the
 * per-class answer is memoised.
 */
@Component
public class PluginOwnership {

    private static final Logger log = LoggerFactory.getLogger(PluginOwnership.class);

    /** The answer for one class. */
    public record Owner(Kind kind, LoadedPlugin plugin) {

        /** Where a class came from. */
        public enum Kind {
            /** A plugin's own top-level jar — the class belongs to {@link #plugin()}. */
            PLUGIN,
            /** A plugin's {@code lib/} jar — shared library code; {@link #plugin()} is the first plugin bundling it. */
            LIBRARY,
            /** Not from any plugin jar: kernel code, the JDK, a test class. {@link #plugin()} is {@code null}. */
            NONE
        }

        static final Owner NONE_OWNER = new Owner(Kind.NONE, null);

        /** The owning plugin's id when this is a {@link Kind#PLUGIN} answer, else empty. */
        public Optional<String> pluginId() {
            return kind == Kind.PLUGIN ? Optional.of(plugin.id()) : Optional.empty();
        }
    }

    private final ObjectProvider<PluginRuntime> runtimeProvider;
    private volatile Index index;
    private final Map<Class<?>, Owner> memo = new ConcurrentHashMap<>();

    public PluginOwnership(ObjectProvider<PluginRuntime> runtimeProvider) {
        this.runtimeProvider = runtimeProvider;
    }

    /** Over an explicit runtime — for tests and for callers outside the context (the quarantine listener). */
    public static PluginOwnership of(PluginRuntime runtime) {
        PluginOwnership ownership = new PluginOwnership(null);
        ownership.index = Index.build(runtime == null ? List.of() : runtime.loaded());
        return ownership;
    }

    /** Which plugin {@code type} belongs to (see the class contract). Never {@code null}. */
    public Owner ownerOf(Class<?> type) {
        if (type == null) {
            return Owner.NONE_OWNER;
        }
        Class<?> user = ClassUtils.getUserClass(type);
        return memo.computeIfAbsent(user, this::lookup);
    }

    /** The owning plugin's id, or empty for kernel/library/unknown code. */
    public Optional<String> pluginIdOf(Class<?> type) {
        return ownerOf(type).pluginId();
    }

    /**
     * The 0-based load position of {@code pluginId} (the topological order the loader registered plugins in), or
     * {@link Integer#MAX_VALUE} when it is not a loaded plugin. Used to decide which of two colliding plugin
     * contributions is the "later" one.
     */
    public int loadIndex(String pluginId) {
        return index().loadOrder.getOrDefault(pluginId, Integer.MAX_VALUE);
    }

    /** The loaded plugin with this id, if it loaded this boot. */
    public Optional<LoadedPlugin> plugin(String pluginId) {
        return Optional.ofNullable(index().byId.get(pluginId));
    }

    /** Every plugin loaded this boot, in load order. */
    public List<LoadedPlugin> loaded() {
        return index().loaded;
    }

    private Owner lookup(Class<?> type) {
        Path jar = codeSourceOf(type);
        if (jar == null) {
            return Owner.NONE_OWNER;
        }
        Index current = index();
        LoadedPlugin own = current.ownJars.get(jar);
        if (own != null) {
            return new Owner(Owner.Kind.PLUGIN, own);
        }
        LoadedPlugin library = current.libJars.get(jar);
        return library != null ? new Owner(Owner.Kind.LIBRARY, library) : Owner.NONE_OWNER;
    }

    private Index index() {
        Index current = index;
        if (current == null) {
            synchronized (this) {
                current = index;
                if (current == null) {
                    PluginRuntime runtime = runtimeProvider == null ? null : runtimeProvider.getIfAvailable();
                    current = Index.build(runtime == null ? List.of() : runtime.loaded());
                    index = current;
                }
            }
        }
        return current;
    }

    /** The real path of the jar (or directory) {@code type} was loaded from, or {@code null} when unknown. */
    static Path codeSourceOf(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return null; // the JDK, or the kernel's own nested boot-jar URL — never a plugin jar
            }
            return realPath(Path.of(location.toURI()));
        } catch (SecurityException | URISyntaxException | IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missing) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** The immutable jar → plugin maps for one boot. */
    private record Index(Map<Path, LoadedPlugin> ownJars, Map<Path, LoadedPlugin> libJars,
                         Map<String, LoadedPlugin> byId, Map<String, Integer> loadOrder, List<LoadedPlugin> loaded) {

        static Index build(List<LoadedPlugin> loaded) {
            Map<Path, LoadedPlugin> own = new HashMap<>();
            Map<Path, LoadedPlugin> lib = new HashMap<>();
            Map<String, LoadedPlugin> byId = new HashMap<>();
            Map<String, Integer> order = new HashMap<>();
            int position = 0;
            for (LoadedPlugin plugin : loaded) {
                byId.put(plugin.id(), plugin);
                order.put(plugin.id(), position++);
                for (Path jar : jarsIn(plugin.installDir())) {
                    own.putIfAbsent(jar, plugin);
                }
                for (Path jar : jarsIn(plugin.installDir().resolve("lib"))) {
                    lib.putIfAbsent(jar, plugin);
                }
            }
            return new Index(Collections.unmodifiableMap(own), Collections.unmodifiableMap(lib),
                    Collections.unmodifiableMap(byId), Collections.unmodifiableMap(order), List.copyOf(loaded));
        }

        private static List<Path> jarsIn(Path dir) {
            if (dir == null || !Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> entries = Files.list(dir)) {
                return entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(Files::isRegularFile)
                        .map(PluginOwnership::realPath)
                        .toList();
            } catch (IOException e) {
                log.warn("could not list plugin jars under {}: {}", dir, e.toString());
                return List.of();
            }
        }
    }
}
