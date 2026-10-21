package mx.quest.runway.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import mx.quest.runway.web.RunwayHttpCacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves each loaded plugin's bundled static assets from its own namespace — {@code /__plugin/<id>/static/**}
 * maps to {@code <installDir>/static/} — so plugin assets are operator-editable on disk and never copied into a
 * tenant (the self-containment law: plugin code/assets belong to the plugin, served from its namespace). The id
 * in the path is a loaded plugin's id (already path-safe from discovery), and Spring's resource resolver confines
 * the {@code **} within the plugin's static dir, so a request can never traverse into another plugin or the
 * external tree. {@code /__plugin} is a reserved prefix an {@code EndpointContributor} cannot claim.
 *
 * <h2>Caching (since 1.0.0-rc5)</h2>
 * A served file carries {@code runway.http-cache.plugin-assets} (default {@code public, max-age=3600}) and a strong
 * ETag of its <b>content</b>; it carries no {@code Last-Modified}. Before 1.0.0-rc5 it carried neither rule nor ETag, so
 * Spring Security's default {@code no-store} made a browser download a plugin's CSS or script again on every page
 * view. Why a content ETag and not the file date: plugin archives are built reproducibly, so every release stamps
 * its files with the same fixed date, and a {@code Last-Modified} validator would answer {@code 304} to an upgraded
 * file. The hash is computed once per file state (path, size, modification time) and remembered; an upgrade means
 * a restart, which starts with an empty memory. A 404 gets no cache header (Spring applies it to a found file only).
 */
@Configuration(proxyBeanMethods = false)
public class PluginStaticResourceConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PluginStaticResourceConfig.class);

    private final PluginRuntime runtime;
    private final RunwayHttpCacheProperties httpCache;
    private final Map<String, String> etags = new ConcurrentHashMap<>();

    public PluginStaticResourceConfig(ObjectProvider<PluginRuntime> runtimeProvider,
                                      ObjectProvider<RunwayHttpCacheProperties> httpCacheProvider) {
        this.runtime = runtimeProvider.getIfAvailable(
                () -> PluginRuntime.empty(PluginStaticResourceConfig.class.getClassLoader()));
        this.httpCache = httpCacheProvider.getIfAvailable(RunwayHttpCacheProperties::new);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (runtime.loaded().isEmpty()) {
            return;
        }
        CacheControl cacheControl = cacheControl(httpCache.pluginAssetsOrDefault());
        // Win over the page controller's catch-all @GetMapping("/**"); non-matching paths fall through to it.
        // (Boot's default /** static mapping is off — spring.web.resources.add-mappings: false — so raising the
        // registry order cannot pull a default handler ahead of the controllers.)
        registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        for (LoadedPlugin plugin : runtime.loaded()) {
            Path staticDir = plugin.installDir().resolve("static").toAbsolutePath().normalize();
            String location = staticDir.toUri().toString();
            if (!location.endsWith("/")) {
                location += "/";
            }
            registry.addResourceHandler("/__plugin/" + plugin.id() + "/static/**")
                    .addResourceLocations(location)
                    .setCacheControl(cacheControl)
                    .setUseLastModified(false)
                    .setEtagGenerator(this::etag);
        }
    }

    /**
     * The strong ETag of a file's content (SHA-256, first 128 bits, hex), remembered per path, size and
     * modification time so a file is hashed once until it changes. {@code null} (no ETag) when it cannot be read.
     */
    String etag(Resource resource) {
        try {
            String key = resource.getURI() + "|" + resource.contentLength() + "|" + resource.lastModified();
            return etags.computeIfAbsent(key, k -> hash(resource));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String hash(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash " + resource.getDescription(), e);
        }
    }

    /**
     * Turns the configured {@code Cache-Control} text into Spring's {@link CacheControl}, which is what the resource
     * handler applies (and applies to a found file only). Accepts the directives that type can express:
     * {@code public}, {@code private}, {@code no-cache} (alone, not with {@code max-age} or {@code no-store}),
     * {@code no-store}, {@code must-revalidate}, {@code proxy-revalidate}, {@code no-transform}, {@code immutable},
     * {@code max-age}, {@code s-maxage}, {@code stale-while-revalidate} and {@code stale-if-error}; at least one
     * of {@code max-age}, {@code no-cache} or {@code no-store}. Anything else, or a malformed number, logs a
     * warning and uses the default {@code public, max-age=3600}: a typo must not take the plugin files' caching
     * back to {@code no-store}, nor fail the start.
     */
    static CacheControl cacheControl(String value) {
        CacheControl parsed = parse(value);
        if (parsed != null) {
            return parsed;
        }
        log.warn("runway.http-cache.plugin-assets '{}' is not a Cache-Control value Runway can apply to plugin files; "
                + "using public, max-age=3600", value);
        return parse("public, max-age=3600");
    }

    /** The parsed value, or {@code null} when a directive is unknown, a number is bad, or nothing sets freshness. */
    private static CacheControl parse(String value) {
        CacheControl base = null;
        List<UnaryOperator<CacheControl>> modifiers = new ArrayList<>();
        boolean noCache = false;
        try {
            for (String raw : value.split(",")) {
                String directive = raw.strip().toLowerCase(Locale.ROOT);
                if (directive.isEmpty()) {
                    continue;
                }
                int eq = directive.indexOf('=');
                String name = eq < 0 ? directive : directive.substring(0, eq).strip();
                Duration seconds = eq < 0 ? null
                        : Duration.ofSeconds(Long.parseUnsignedLong(directive.substring(eq + 1).strip()));
                switch (name) {
                    // CacheControl starts from one of three factories; the other directives chain onto it.
                    case "no-store" -> base = CacheControl.noStore();
                    case "max-age" -> {
                        if (base == null) {
                            base = CacheControl.maxAge(required(seconds));
                        }
                    }
                    case "no-cache" -> noCache = true;
                    case "public" -> modifiers.add(CacheControl::cachePublic);
                    case "private" -> modifiers.add(CacheControl::cachePrivate);
                    case "must-revalidate" -> modifiers.add(CacheControl::mustRevalidate);
                    case "proxy-revalidate" -> modifiers.add(CacheControl::proxyRevalidate);
                    case "no-transform" -> modifiers.add(CacheControl::noTransform);
                    case "immutable" -> modifiers.add(CacheControl::immutable);
                    case "s-maxage" -> {
                        Duration d = required(seconds);
                        modifiers.add(cc -> cc.sMaxAge(d));
                    }
                    case "stale-while-revalidate" -> {
                        Duration d = required(seconds);
                        modifiers.add(cc -> cc.staleWhileRevalidate(d));
                    }
                    case "stale-if-error" -> {
                        Duration d = required(seconds);
                        modifiers.add(cc -> cc.staleIfError(d));
                    }
                    default -> {
                        return null;
                    }
                }
            }
        } catch (IllegalArgumentException e) { // NumberFormatException included, and a missing number
            return null;
        }
        if (base == null) {
            if (!noCache) {
                return null; // no freshness directive at all: nothing Runway would want to send for plugin files
            }
            base = CacheControl.noCache();
        } else if (noCache) {
            return null; // Spring's CacheControl cannot add no-cache to max-age or no-store
        }
        for (UnaryOperator<CacheControl> modifier : modifiers) {
            base = modifier.apply(base);
        }
        return base;
    }

    private static Duration required(Duration seconds) {
        if (seconds == null) {
            throw new IllegalArgumentException("missing seconds");
        }
        return seconds;
    }
}
