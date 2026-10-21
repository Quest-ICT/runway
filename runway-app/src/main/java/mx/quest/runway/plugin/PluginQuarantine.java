package mx.quest.runway.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionOverrideException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;

/**
 * The <b>wiring quarantine</b>: turns "one plugin breaks the boot" into "one plugin is
 * Blocked", without restarting anything inside the JVM.
 *
 * <ol>
 *   <li>When the context fails to refresh and the failing bean definition or bean class <b>belongs to a
 *       non-essential plugin</b> ({@link #attribute}), the kernel writes
 *       {@code external/plugin-quarantine/<id>.yml} — the plugin's version, the SHA-256 of its own jars, the reason
 *       and the time — logs an ERROR, and exits with the supervisor's relaunch code (42).</li>
 *   <li>On the next boot the loader reads the markers ({@link #blocked}) and refuses each quarantined plugin
 *       <b>before wiring</b>, with the recorded reason, so the platform comes up without it.</li>
 *   <li>The marker is cleared by installing a different version (a different version or different jar bytes no
 *       longer match it, so it is deleted as stale) or by <b>Retry</b> on the plugins screen ({@link #clear}).</li>
 * </ol>
 *
 * A failure that cannot be attributed to a plugin stays an ordinary boot failure — exit code 1, no marker — so a
 * kernel defect is never hidden behind a plugin. The essential plugin is never quarantined: it ships with the
 * kernel, and quarantining the security floor would only trade a loud boot failure for fail-closed mode.
 */
public final class PluginQuarantine {

    private static final Logger log = LoggerFactory.getLogger(PluginQuarantine.class);

    /** The folder under the external root holding one marker per quarantined plugin. */
    public static final String DIR = "plugin-quarantine";

    /** Set once a marker has been written for the failing boot; read by {@code RunwayApplication.main}. */
    private static volatile String quarantinedThisBoot;

    /** What a marker file holds. */
    public record Marker(String id, String version, String sha256, String reason, String at) {
    }

    private PluginQuarantine() {
    }

    // ---- next boot: refuse quarantined plugins ------------------------------------------------------------

    /**
     * The quarantined plugins among {@code candidates}, id → operator-readable reason. A marker whose version or
     * jar digest no longer matches the installed plugin is <b>stale</b> (a different build was installed) and is
     * deleted here; a marker for a plugin that is not installed is left alone (harmless, and it documents what
     * happened).
     */
    public static Map<String, String> blocked(Path externalRoot, List<LoadedPlugin> candidates) {
        return blocked(externalRoot, candidates, true);
    }

    /**
     * As {@link #blocked(Path, List)}; with {@code clearStale = false} a stale marker is only skipped, not deleted —
     * the form the plugins screen's next-boot preview uses, so a read model never changes the disk.
     */
    public static Map<String, String> blocked(Path externalRoot, List<LoadedPlugin> candidates, boolean clearStale) {
        Map<String, String> blocked = new LinkedHashMap<>();
        for (LoadedPlugin plugin : candidates) {
            Optional<Marker> marker = read(externalRoot, plugin.id());
            if (marker.isEmpty()) {
                continue;
            }
            Marker m = marker.get();
            String digest = digest(plugin.installDir());
            if (!plugin.descriptor().version().equals(m.version()) || !digest.equals(m.sha256())) {
                if (clearStale) {
                    log.info("plugin '{}' was quarantined as v{}; v{} (or different jars) is installed now — "
                            + "clearing the stale quarantine marker", plugin.id(), m.version(),
                            plugin.descriptor().version());
                    clear(externalRoot, plugin.id());
                }
                continue;
            }
            blocked.put(plugin.id(), "quarantined: it broke the platform's startup at " + m.at() + " (" + m.reason()
                    + "). Install a fixed version, or use Retry on the plugins screen, then restart.");
        }
        return blocked;
    }

    /** The marker for {@code pluginId}, if one exists and parses. */
    public static Optional<Marker> read(Path externalRoot, String pluginId) {
        Path file = markerFile(externalRoot, pluginId);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Marker marker = Yaml.read(in, Marker.class);
            return Optional.ofNullable(marker);
        } catch (IOException | RuntimeException unreadable) {
            log.warn("ignoring an unreadable quarantine marker {}: {}", file, unreadable.toString());
            return Optional.empty();
        }
    }

    /** Deletes the marker for {@code pluginId} (Retry, or a new version installed). Returns whether one existed. */
    public static boolean clear(Path externalRoot, String pluginId) {
        Path file = markerFile(externalRoot, pluginId);
        try {
            return file != null && Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("deleting quarantine marker " + file, e);
        }
    }

    // ---- failing boot: attribute, write, exit 42 ----------------------------------------------------------

    /**
     * Writes the marker for {@code plugin} and remembers that this boot must end with the relaunch code.
     */
    public static void quarantine(Path externalRoot, LoadedPlugin plugin, String reason) {
        Path file = markerFile(externalRoot, plugin.id());
        if (file == null) {
            return;
        }
        Marker marker = new Marker(plugin.id(), plugin.descriptor().version(), digest(plugin.installDir()),
                oneLine(reason), Instant.now().toString());
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.write(file, out -> Yaml.write(out, marker));
        } catch (IOException e) {
            log.error("could not write the quarantine marker for plugin '{}' — the next boot will try it again",
                    plugin.id(), e);
            return;
        }
        quarantinedThisBoot = plugin.id();
        log.error("plugin '{}' v{} broke the platform's startup and is QUARANTINED: {}. The marker {} makes the next "
                + "boot load without it; exiting with code {} so the supervisor relaunches.", plugin.id(),
                plugin.descriptor().version(), marker.reason(), file, mx.quest.runway.wizard.Restarter.RESTART_EXIT_CODE);
    }

    /**
     * The exit code a failed {@code SpringApplication.run} should end the JVM with: the relaunch code when this boot
     * quarantined a plugin, else empty (an ordinary failure).
     */
    public static java.util.OptionalInt exitCodeAfterFailure() {
        return quarantinedThisBoot == null ? java.util.OptionalInt.empty()
                : java.util.OptionalInt.of(mx.quest.runway.wizard.Restarter.RESTART_EXIT_CODE);
    }

    /** The plugin this boot quarantined, if any (tests). */
    static Optional<String> quarantinedThisBoot() {
        return Optional.ofNullable(quarantinedThisBoot);
    }

    /** Forgets this boot's decision (tests that run several boots in one JVM). */
    static void resetForTests() {
        quarantinedThisBoot = null;
    }

    /**
     * The non-essential plugin a startup failure belongs to, if it can be told: the cause chain is walked for a
     * bean-definition clash ({@link BeanDefinitionOverrideException} — either side), a bean that failed to be
     * created ({@link BeanCreationException}) or a bad definition ({@link BeanDefinitionStoreException}); each
     * named bean's definition is mapped to the class that declares it (the {@code @Bean} method's class, or the
     * bean class), and that class to its plugin by code source. The innermost attributable bean wins: a kernel bean
     * that failed only because a plugin bean it needed failed is not the culprit.
     */
    public static Optional<LoadedPlugin> attribute(Throwable failure, ConfigurableListableBeanFactory beanFactory,
                                                   PluginOwnership ownership, ClassLoader classLoader) {
        LoadedPlugin found = null;
        Set<Throwable> seen = new HashSet<>();
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            LoadedPlugin here = null;
            if (t instanceof BeanDefinitionOverrideException clash) {
                here = ownerOf(clash.getBeanDefinition(), ownership, classLoader)
                        .or(() -> ownerOf(clash.getExistingDefinition(), ownership, classLoader)).orElse(null);
            } else if (t instanceof BeanCreationException creation && creation.getBeanName() != null) {
                here = ownerOfBean(creation.getBeanName(), beanFactory, ownership, classLoader).orElse(null);
            } else if (t instanceof BeanDefinitionStoreException store && store.getBeanName() != null) {
                here = ownerOfBean(store.getBeanName(), beanFactory, ownership, classLoader).orElse(null);
            }
            if (here != null) {
                found = here; // keep walking: the innermost attributable cause is the culprit
            }
        }
        if (found != null && EssentialAllowlist.contains(found.id())) {
            return Optional.empty();
        }
        return Optional.ofNullable(found);
    }

    private static Optional<LoadedPlugin> ownerOfBean(String beanName, ConfigurableListableBeanFactory beanFactory,
                                                      PluginOwnership ownership, ClassLoader classLoader) {
        if (beanFactory == null || !beanFactory.containsBeanDefinition(beanName)) {
            return Optional.empty();
        }
        return ownerOf(beanFactory.getBeanDefinition(beanName), ownership, classLoader);
    }

    /** The plugin whose own jar declares {@code definition} (its {@code @Bean} method's class, else its class). */
    static Optional<LoadedPlugin> ownerOf(BeanDefinition definition, PluginOwnership ownership,
                                          ClassLoader classLoader) {
        if (definition == null) {
            return Optional.empty();
        }
        String declaring = null;
        if (definition instanceof AnnotatedBeanDefinition annotated && annotated.getFactoryMethodMetadata() != null) {
            declaring = annotated.getFactoryMethodMetadata().getDeclaringClassName();
        }
        for (String candidate : new String[] {declaring, definition.getBeanClassName()}) {
            if (candidate == null) {
                continue;
            }
            try {
                Class<?> type = Class.forName(candidate, false, classLoader);
                PluginOwnership.Owner owner = ownership.ownerOf(type);
                if (owner.kind() == PluginOwnership.Owner.Kind.PLUGIN) {
                    return Optional.of(owner.plugin());
                }
            } catch (ClassNotFoundException | LinkageError unloadable) {
                // not resolvable from the plugin loader — try the next candidate
            }
        }
        return Optional.empty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static Path markerFile(Path externalRoot, String pluginId) {
        if (externalRoot == null || pluginId == null || !PathSafety.isSafeId(pluginId)) {
            return null;
        }
        return externalRoot.resolve(DIR).resolve(pluginId + ".yml");
    }

    /** SHA-256 over the plugin's own top-level jars (name and bytes, in name order); "" when there are none. */
    static String digest(Path installDir) {
        if (installDir == null || !Files.isDirectory(installDir)) {
            return "";
        }
        try (Stream<Path> entries = Files.list(installDir)) {
            List<Path> jars = entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile).sorted().toList();
            if (jars.isEmpty()) {
                return "";
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Path jar : jars) {
                sha.update(jar.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(Files.readAllBytes(jar));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String oneLine(String text) {
        String flat = text == null ? "unknown" : text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 500 ? flat : flat.substring(0, 500) + "…";
    }
}
