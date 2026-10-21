package mx.quest.runway.plugin;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The rules for what a non-essential plugin may put into the host's shared bean namespace and Spring
 * infrastructure, checked twice: <b>before refresh</b> by reflection over the plugin's
 * {@code mainClass}, its {@code @Import}s and nested {@code @Configuration} classes, and <b>after refresh</b> over
 * every bean definition that belongs to the plugin (by the jar its declaring class came from), using the bean's
 * real type. From SPI 1.0.0 a violation <b>blocks</b> the plugin: before refresh it is refused with the reason
 * ({@code PluginContextInitializer}); after refresh it is quarantined and the boot ends with the relaunch code
 * ({@link PluginCompliance}). (Until 1.0.0-rc1 both were warnings.)
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Bean names</b> start with the plugin id in lowerCamelCase ({@code search-lucene} →
 *       {@code searchLucene…}); the host registers the {@code mainClass} itself as {@code plugin:<id>}. Plugins
 *       share one namespace with the kernel and each other, so an unprefixed name is a collision waiting for an
 *       upgrade.</li>
 *   <li><b>Allowed</b>: SPI implementations and plain beans; {@code @Controller}s whose every mapping lies under
 *       {@code /__admin/<id>} or {@code /__app/<id>} (so the realm chains and CSRF repositories apply);
 *       {@code @ControllerAdvice} restricted to the plugin's own packages, types or annotations.</li>
 *   <li><b>Not allowed</b>: {@code SecurityFilterChain}s, servlet {@code Filter}s and {@code FilterRegistrationBean}s
 *       (and the host's {@code FilterContribution}), {@code BeanPostProcessor}s and {@code BeanFactoryPostProcessor}s,
 *       and an unrestricted {@code @ControllerAdvice} — each can reach into every request or every bean of the
 *       platform.</li>
 * </ul>
 */
public final class PluginSurfaceCheck {

    /** Types a non-essential plugin may not contribute, by name (so the check needs none of them on the classpath). */
    static final List<String> FORBIDDEN_TYPES = List.of(
            "org.springframework.security.web.SecurityFilterChain",
            "jakarta.servlet.Filter",
            "org.springframework.boot.web.servlet.FilterRegistrationBean",
            "mx.quest.runway.web.FilterContribution");

    private PluginSurfaceCheck() {
    }

    /** {@code search-lucene} → {@code searchLucene}; {@code runway-demo} → {@code runwayDemo}. */
    public static String beanPrefix(String pluginId) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : pluginId.toCharArray()) {
            if (c == '-' || c == '_' || c == '.') {
                upper = out.length() > 0;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : (out.length() == 0 ? Character.toLowerCase(c) : c));
            upper = false;
        }
        return out.toString();
    }

    /** The bean name the host registers a plugin's {@code mainClass} under. */
    public static String mainBeanName(String pluginId) {
        return "plugin:" + pluginId;
    }

    // ---- before refresh -----------------------------------------------------------------------------------

    /**
     * Reflection over {@code mainClass}, everything it {@code @Import}s, and their nested {@code @Configuration}
     * classes: {@code @Bean} names and return types, and controller/advice annotations on imported classes.
     *
     * @return the violations, each one operator-readable line (empty when the plugin complies)
     */
    public static List<String> beforeRefresh(String pluginId, Class<?> mainClass) {
        Set<String> problems = new LinkedHashSet<>();
        String prefix = beanPrefix(pluginId);
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(mainClass));
        Set<Class<?>> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Class<?> type = queue.pop();
            if (!seen.add(type)) {
                continue;
            }
            try {
                Import imports = type.getAnnotation(Import.class);
                if (imports != null) {
                    queue.addAll(List.of(imports.value()));
                }
                for (Class<?> nested : type.getDeclaredClasses()) {
                    if (nested.isAnnotationPresent(Configuration.class)) {
                        queue.add(nested);
                    }
                }
                if (type != mainClass) {
                    checkType(pluginId, type, problems);
                }
                for (Method method : type.getDeclaredMethods()) {
                    Bean bean = method.getAnnotation(Bean.class);
                    if (bean == null) {
                        continue;
                    }
                    String[] names = bean.name().length > 0 ? bean.name() : bean.value();
                    String name = names.length > 0 ? names[0] : method.getName();
                    if (!name.startsWith(prefix)) {
                        problems.add(nameProblem(name, prefix));
                    }
                    String forbidden = forbiddenSupertype(method.getReturnType());
                    if (forbidden != null) {
                        problems.add("bean '" + name + "' is a " + forbidden + ", which a plugin may not contribute");
                    }
                    checkType(pluginId, method.getReturnType(), problems);
                }
            } catch (LinkageError unresolvable) {
                problems.add("could not inspect " + type.getName() + ": " + unresolvable);
            }
        }
        return List.copyOf(problems);
    }

    // ---- after refresh ------------------------------------------------------------------------------------

    /**
     * Every bean definition whose declaring class belongs to a non-essential plugin, checked by the bean's
     * <b>real</b> type (a {@code @Bean} method returning {@code Object} cannot hide a filter here).
     *
     * @return plugin id → its violations (only plugins with any)
     */
    public static Map<String, List<String>> afterRefresh(ConfigurableListableBeanFactory beanFactory,
                                                         PluginOwnership ownership, ClassLoader classLoader) {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            LoadedPlugin plugin = PluginQuarantine.ownerOf(definition, ownership, classLoader).orElse(null);
            if (plugin == null || EssentialAllowlist.contains(plugin.id())) {
                continue;
            }
            Set<String> problems = found.computeIfAbsent(plugin.id(), id -> new LinkedHashSet<>());
            String prefix = beanPrefix(plugin.id());
            if (!name.equals(mainBeanName(plugin.id())) && !name.startsWith(prefix)) {
                problems.add(nameProblem(name, prefix));
            }
            Class<?> type;
            try {
                type = beanFactory.getType(name, false);
            } catch (RuntimeException | LinkageError unresolvable) {
                continue;
            }
            if (type == null) {
                continue;
            }
            String forbidden = forbiddenSupertype(type);
            if (forbidden != null) {
                problems.add("bean '" + name + "' is a " + forbidden + ", which a plugin may not contribute");
            }
            checkType(plugin.id(), type, problems);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        found.forEach((id, problems) -> {
            if (!problems.isEmpty()) {
                out.put(id, List.copyOf(problems));
            }
        });
        return out;
    }

    // ---- shared -------------------------------------------------------------------------------------------

    private static String nameProblem(String name, String prefix) {
        return "bean name '" + name + "' does not start with '" + prefix + "' (plugins share the host's bean "
                + "namespace; rename it, e.g. '" + prefix + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                + "')";
    }

    /** The name of the forbidden type {@code type} is (or extends), or {@code null}. */
    static String forbiddenSupertype(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (BeanPostProcessor.class.isAssignableFrom(type)) {
            return "BeanPostProcessor";
        }
        if (BeanFactoryPostProcessor.class.isAssignableFrom(type)) {
            return "BeanFactoryPostProcessor";
        }
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Class<?> current = queue.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (FORBIDDEN_TYPES.contains(current.getName())) {
                return current.getSimpleName();
            }
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass()); // ArrayDeque refuses null: Object and interfaces have none
            }
            queue.addAll(List.of(current.getInterfaces()));
        }
        return null;
    }

    /** Controller mappings and controller-advice restrictions on one contributed type. */
    private static void checkType(String pluginId, Class<?> type, Set<String> problems) {
        ControllerAdvice advice = AnnotatedElementUtils.findMergedAnnotation(type, ControllerAdvice.class);
        if (advice != null && advice.basePackages().length == 0 && advice.basePackageClasses().length == 0
                && advice.assignableTypes().length == 0 && advice.annotations().length == 0
                && advice.value().length == 0) {
            problems.add("@ControllerAdvice " + type.getName() + " is unrestricted — it would apply to every "
                    + "kernel controller; restrict it to the plugin's packages, types or annotations");
        }
        if (AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
            for (String path : mappings(type)) {
                if (!allowedConsolePath(pluginId, path)) {
                    problems.add("controller " + type.getSimpleName() + " maps '" + path + "', outside /__admin/"
                            + pluginId + " and /__app/" + pluginId);
                }
            }
        }
    }

    /** Every path {@code type} maps: class-level prefixes combined with each handler method's paths. */
    static List<String> mappings(Class<?> type) {
        RequestMapping classLevel = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        List<String> prefixes = classLevel == null || classLevel.path().length == 0 ? List.of("")
                : List.of(classLevel.path());
        List<String> out = new ArrayList<>();
        for (Method method : type.getMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            List<String> paths = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
            for (String prefix : prefixes) {
                for (String path : paths) {
                    String joined = join(prefix, path);
                    out.add(joined.isEmpty() ? "/" : joined);
                }
            }
        }
        return out;
    }

    private static String join(String prefix, String path) {
        if (prefix.isEmpty()) {
            return path;
        }
        if (path.isEmpty()) {
            return prefix;
        }
        return (prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)
                + (path.startsWith("/") ? path : "/" + path);
    }

    /** Whether {@code path} lies under {@code /__admin/<id>} or {@code /__app/<id>} (case-sensitive, segment-wise). */
    static boolean allowedConsolePath(String pluginId, String path) {
        for (String root : List.of("/__admin/" + pluginId, "/__app/" + pluginId)) {
            if (path.equals(root) || path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }
}
