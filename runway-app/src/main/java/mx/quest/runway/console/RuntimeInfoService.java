package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.backup.BackupProperties;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.i18n.I18nProperties;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.ActivePasswordStrength;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.web.ClientIp;
import mx.quest.runway.web.CompressionFilter;
import mx.quest.runway.web.ProxyResolution;
import mx.quest.runway.web.RequestOrigin;
import mx.quest.runway.web.RunwayProxyProperties;
import mx.quest.runway.web.RunwayTlsProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.SpringVersion;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * A read-only snapshot of the effective configuration and the runtime environment, for the app-realm
 * <b>About Runway</b> screen ({@link AppAboutController}). It is a pure query bean — it computes but never
 * mutates, holds no state, and touches no tenant data.
 *
 * <p><b>Secret hygiene.</b> The "effective configuration" is a <em>curated whitelist</em> of non-secret,
 * operator-relevant settings read off the bound {@code @ConfigurationProperties} beans — never a dump of the
 * Spring {@code Environment}'s property sources. Nothing that carries a secret (the TLS keystore password, the
 * metrics scrape token, the {@code runway.security.secret-key}, plugin secret settings) is ever surfaced;
 * secret-adjacent state is shown only as a boolean "configured / not configured" (e.g. via
 * {@link SecretCipher#isAvailable()}), following the platform's "render whether it is set, never its value"
 * rule. Because the list is curated, no masking/redaction is needed.
 *
 * <p>Everything under "runtime environment" describes <b>the node serving the request</b> (PID, uptime, heap,
 * host name, active profiles) — relevant when more than one node runs the platform.
 */
@Service
public class RuntimeInfoService {

    /** A single labelled row of the About tables. {@code name} is a message key (config/runtime rows) or a
     *  literal product name (component rows); {@code value} is a display-ready string. */
    public record NameValue(String name, String value) {}

    private static final long MIB = 1024L * 1024L;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());
    /** Matches a trailing {@code -<version>} on a jar file name (used when a package has no Implementation-Version). */
    private static final Pattern JAR_VERSION = Pattern.compile("-(\\d[\\w.+-]*)$");

    private final ActivePasswordStrength activeStrength;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final Environment environment;
    private final RunwayTlsProperties tls;
    private final RunwayProxyProperties proxy;
    private final BackupProperties backups;
    private final I18nProperties i18n;
    private final StorageLayout layout;
    private final SecretCipher secretCipher;
    private final CompressionFilter compression;
    private final PlatformSettingsStore platform;

    public RuntimeInfoService(ObjectProvider<BuildProperties> buildProperties, Environment environment,
                              RunwayTlsProperties tls,
                              RunwayProxyProperties proxy, BackupProperties backups, I18nProperties i18n,
                              StorageLayout layout, SecretCipher secretCipher,
                              CompressionFilter compression, PlatformSettingsStore platform,
                              ActivePasswordStrength activeStrength) {
        this.activeStrength = activeStrength;
        this.buildProperties = buildProperties;
        this.environment = environment;
        this.tls = tls;
        this.proxy = proxy;
        this.backups = backups;
        this.i18n = i18n;
        this.layout = layout;
        this.secretCipher = secretCipher;
        this.compression = compression;
        this.platform = platform;
    }

    /** The running application version — from the build-info bean, else the jar manifest, else {@code "dev"}. */
    public String version() {
        BuildProperties bp = buildProperties.getIfAvailable();
        if (bp != null && bp.getVersion() != null && !bp.getVersion().isBlank()) {
            return bp.getVersion();
        }
        String impl = getClass().getPackage() == null ? null : getClass().getPackage().getImplementationVersion();
        return impl != null && !impl.isBlank() ? impl : "dev";
    }

    /** The build timestamp from the build-info bean, formatted; {@code "—"} when build-info is absent. */
    public String buildTime() {
        BuildProperties bp = buildProperties.getIfAvailable();
        return bp != null && bp.getTime() != null ? TS.format(bp.getTime()) : "—";
    }

    /**
     * The curated, non-secret effective configuration. Each row's {@code name} is a message key
     * ({@code op.about.cfg.*}); values are formatted for display and deliberately exclude every secret.
     */
    public List<NameValue> effectiveConfig() {
        List<NameValue> rows = new ArrayList<>();
        rows.add(new NameValue("op.about.cfg.tls",
                tls.isEnabled() ? "terminated here (embedded)" : "at the reverse proxy"));
        rows.add(new NameValue("op.about.cfg.port", serverPort()));
        rows.add(new NameValue("op.about.cfg.proxy", proxySummary()));
        rows.add(new NameValue("op.about.cfg.compression", compression.summary()));
        rows.add(new NameValue("op.about.cfg.pw", passwordSummary()));
        rows.add(new NameValue("op.about.cfg.secret", secretCipher.isAvailable() ? "configured" : "not configured"));
        rows.add(new NameValue("op.about.cfg.dataDir", pathSummary(layout.external())));
        rows.add(new NameValue("op.about.cfg.backupsDir", pathSummary(layout.backups())));
        rows.add(new NameValue("op.about.cfg.schedule", scheduleSummary()));
        rows.add(new NameValue("op.about.cfg.retention", retentionSummary()));
        rows.add(new NameValue("op.about.cfg.offsite",
                backups.getOffsiteDir().isBlank() ? "off" : backups.getOffsiteDir()));
        rows.add(new NameValue("op.about.cfg.i18n",
                "public pages: every request · other: "
                        + (i18n.getCacheSeconds() == 0 ? "every access" : "every " + i18n.getCacheSeconds() + " s")));
        return rows;
    }

    /**
     * The profile new passwords are hashed with now, and the saved Platform settings choice when it differs
     * (it applies on the next launch). Until 1.0.0 this row showed the {@code application.yml} default even when
     * Platform settings had changed it.
     */
    String passwordSummary() {
        String inForce = activeStrength.strength().name();
        String saved = platform.get().strength().map(v -> v.trim().toUpperCase(java.util.Locale.ROOT)).orElse(null);
        if (saved != null && !saved.equals(inForce)) {
            return "Argon2id · " + inForce + " (" + saved + " is saved and applies after the next restart)";
        }
        return "Argon2id · " + inForce;
    }

    /**
     * A data path as configured, followed by where it really is when that differs (an installed release reaches
     * {@code /opt/runway-data} through links under {@code /opt/runway-<version>}).
     */
    static String pathSummary(java.nio.file.Path configured) {
        try {
            java.nio.file.Path real = configured.toRealPath();
            return real.equals(configured.toAbsolutePath().normalize())
                    ? configured.toString() : configured + " → " + real;
        } catch (java.io.IOException | SecurityException unresolved) {
            return configured.toString();
        }
    }

    /** The "Host &amp; JVM" table describing the node serving the request. Row names are {@code op.about.rt.*} keys. */
    /**
     * The current request as Runway sees it: the connection it came in on, the {@code X-Forwarded-For} chain, the
     * client address that rate limits, lockouts and logs use, and the origin. Lets an operator check a reverse-proxy
     * setup ({@code runway.proxy.*}) from the browser: behind a correctly trusted proxy the client row shows the
     * operator's own public address, not the proxy's. Values are the operator's own request data, nothing secret.
     */
    public List<NameValue> request(HttpServletRequest request) {
        List<NameValue> rows = new ArrayList<>();
        ProxyResolution resolution = ProxyResolution.of(request);
        if (resolution == null) {
            rows.add(new NameValue("op.about.req.client", ClientIp.of(request)));
        } else {
            rows.add(new NameValue("op.about.req.peer", resolution.peer() + (resolution.peerTrusted()
                    ? " (trusted proxy)" : " (not a trusted proxy)")));
            String chain = resolution.forwardedFor().text();
            rows.add(new NameValue("op.about.req.forwardedFor", chain.isEmpty() ? "none"
                    : resolution.honoured() ? chain : chain + " (ignored)"));
            rows.add(new NameValue("op.about.req.client", resolution.clientIp()));
        }
        String origin = RequestOrigin.of(request);
        rows.add(new NameValue("op.about.req.origin", origin.isEmpty() ? "—" : origin));
        return rows;
    }

    public List<NameValue> runtime() {
        RuntimeMXBean rb = ManagementFactory.getRuntimeMXBean();
        Runtime rt = Runtime.getRuntime();
        String[] profiles = environment.getActiveProfiles();
        List<NameValue> rows = new ArrayList<>();
        rows.add(new NameValue("op.about.rt.node", nodeName()));
        rows.add(new NameValue("op.about.rt.profiles", profiles.length == 0 ? "default" : String.join(", ", profiles)));
        rows.add(new NameValue("op.about.rt.java", prop("java.version") + " (" + prop("java.vendor") + ")"));
        rows.add(new NameValue("op.about.rt.jvm", prop("java.vm.name") + " " + prop("java.vm.version")));
        rows.add(new NameValue("op.about.rt.os",
                prop("os.name") + " " + prop("os.version") + " (" + prop("os.arch") + ")"));
        rows.add(new NameValue("op.about.rt.cpus", Integer.toString(rt.availableProcessors())));
        rows.add(new NameValue("op.about.rt.heap",
                (rt.totalMemory() - rt.freeMemory()) / MIB + " MiB used / " + rt.maxMemory() / MIB + " MiB max"));
        rows.add(new NameValue("op.about.rt.disk", diskSummary()));
        rows.add(new NameValue("op.about.rt.uptime", humanDuration(rb.getUptime())));
        rows.add(new NameValue("op.about.rt.started", TS.format(Instant.ofEpochMilli(rb.getStartTime()))));
        rows.add(new NameValue("op.about.rt.pid", Long.toString(ProcessHandle.current().pid())));
        rows.add(new NameValue("op.about.rt.host", hostName()));
        rows.add(new NameValue("op.about.rt.timezone", ZoneId.systemDefault().getId()));
        rows.add(new NameValue("op.about.rt.locale", Locale.getDefault().toLanguageTag()));
        return rows;
    }

    /**
     * The "Bundled components" table — the <b>notable</b> third-party libraries and their versions (a curated
     * set, not the full ~100-jar transitive closure). Row names are literal product names (not translated). A
     * library whose version cannot be resolved is silently omitted (a missing optional dependency must never
     * break the page). Grouped: platform/web, data, observability, crypto/cache, rendering, logging.
     */
    public List<NameValue> components() {
        List<NameValue> rows = new ArrayList<>();
        addIf(rows, "Runway", version());
        addIf(rows, "Spring Boot", SpringBootVersion.getVersion());
        addIf(rows, "Spring Framework", SpringVersion.getVersion());
        addIf(rows, "Spring Security", versionOf("org.springframework.security.core.SpringSecurityCoreVersion"));
        addIf(rows, "Jetty", versionOf("org.eclipse.jetty.server.Server"));
        addIf(rows, "Thymeleaf", versionOf("org.thymeleaf.TemplateEngine"));
        addIf(rows, "attoparser", versionOf("org.attoparser.MarkupParser"));
        addIf(rows, "unbescape", versionOf("org.unbescape.html.HtmlEscape"));
        addIf(rows, "Jackson", versionOf("tools.jackson.databind.ObjectMapper"));
        addIf(rows, "SnakeYAML", versionOf("org.yaml.snakeyaml.Yaml"));
        addIf(rows, "Micrometer", versionOf("io.micrometer.core.instrument.MeterRegistry"));
        addIf(rows, "Prometheus", versionOf("io.prometheus.metrics.core.metrics.Counter"));
        addIf(rows, "BouncyCastle", versionOf("org.bouncycastle.jce.provider.BouncyCastleProvider"));
        addIf(rows, "Caffeine", versionOf("com.github.benmanes.caffeine.cache.Caffeine"));
        addIf(rows, "jsoup", versionOf("org.jsoup.Jsoup"));
        addIf(rows, "jsvg", versionOf("com.github.weisj.jsvg.SVGDocument"));
        addIf(rows, "brotli4j", versionOf("com.aayushatharva.brotli4j.Brotli4jLoader"));
        addIf(rows, "CodeMirror", classpathVersion("codemirror.version"));
        addIf(rows, "SLF4J", versionOf("org.slf4j.LoggerFactory"));
        addIf(rows, "Logback", versionOf("ch.qos.logback.classic.LoggerContext"));
        return rows;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** The effective HTTP port — the actually-bound port when known ({@code local.server.port}), else configured. */
    private String serverPort() {
        String bound = environment.getProperty("local.server.port");
        if (bound != null && !bound.isBlank()) {
            return bound;
        }
        return environment.getProperty("server.port", "8080");
    }

    private String proxySummary() {
        if (!proxy.isForwardedHeaders()) {
            return "forwarded headers ignored";
        }
        int n = proxy.getTrustedCidrs() == null ? 0 : proxy.getTrustedCidrs().size();
        return n == 0 ? "direct connection only" : n + " trusted CIDR(s)";
    }

    private String scheduleSummary() {
        BackupProperties.Schedule s = backups.getSchedule();
        return s.isEnabled() ? "enabled · " + s.getCron() : "off (manual only)";
    }

    private String retentionSummary() {
        BackupProperties.Retention r = backups.getRetention();
        String count = r.getMaxCount() > 0 ? r.getMaxCount() + " newest" : "unlimited";
        return r.getMaxAgeDays() > 0 ? count + " · " + r.getMaxAgeDays() + "d" : count;
    }

    private String diskSummary() {
        try {
            FileStore fs = Files.getFileStore(layout.external());
            return humanBytes(fs.getUsableSpace()) + " free of " + humanBytes(fs.getTotalSpace());
        } catch (Exception unavailable) {
            return "?";
        }
    }

    /**
     * The logical node name — the configured {@code runway.node.name}, or the OS host name when that is blank.
     * A human-friendly identity for the node serving the request, distinct from the raw {@code os host name}
     * and worth setting when several nodes run the platform behind a load balancer (so a page/log can be traced
     * to a specific node). Read straight off the {@link Environment} — it is a display-only value.
     */
    private String nodeName() {
        Optional<String> override = platform.get().node();   // Platform settings takes precedence, applied live
        if (override.isPresent()) {
            return override.get();
        }
        String configured = environment.getProperty("runway.node.name");
        return configured != null && !configured.isBlank() ? configured.trim() : hostName();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception unknown) {
            return "?";
        }
    }

    private static String prop(String key) {
        String v = System.getProperty(key);
        return v != null ? v : "?";
    }

    private static void addIf(List<NameValue> rows, String name, String value) {
        if (value != null && !value.isBlank()) {
            rows.add(new NameValue(name, value));
        }
    }

    /** Formats a byte count as GiB (≥ 1 GiB) or MiB, with one decimal — for human-readable disk sizes. */
    private static String humanBytes(long bytes) {
        double gib = bytes / (double) (MIB * 1024);
        if (gib >= 1.0) {
            return String.format(Locale.ROOT, "%.1f GiB", gib);
        }
        return String.format(Locale.ROOT, "%.0f MiB", bytes / (double) MIB);
    }

    /** Compacts a millisecond duration to at most three coarse units (e.g. {@code "3d 4h 12m"}, {@code "5m 8s"}). */
    private static String humanDuration(long millis) {
        Duration d = Duration.ofMillis(millis);
        long days = d.toDays();
        int hours = d.toHoursPart();
        int mins = d.toMinutesPart();
        int secs = d.toSecondsPart();
        if (days > 0) {
            return days + "d " + hours + "h " + mins + "m";
        }
        if (hours > 0) {
            return hours + "h " + mins + "m " + secs + "s";
        }
        if (mins > 0) {
            return mins + "m " + secs + "s";
        }
        return secs + "s";
    }

    /**
     * Best-effort version of a library, identified by a representative class name. Tries the package's
     * {@code Implementation-Version} first; if that is absent or the sentinel {@code "None"} (some jars — e.g.
     * BouncyCastle — ship that, or only a {@code Bundle-Version}), parses the version out of the source jar's
     * file name. Returns {@code null} — never throws — when the class is absent or nothing usable is found, so
     * an optional dependency never breaks the About page.
     */
    static String versionOf(String className) {
        try {
            Class<?> c = Class.forName(className);
            String v = c.getPackage() == null ? null : c.getPackage().getImplementationVersion();
            if (v != null && !v.isBlank() && !"None".equalsIgnoreCase(v)) {
                return v;
            }
            var source = c.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? null
                    : versionFromJarName(source.getLocation().toString());
        } catch (Throwable absentOrUnreadable) {
            return null;
        }
    }

    /**
     * Extracts a version from a code-source location URL by parsing the source jar's file name. Handles a
     * Spring Boot <b>nested</b> jar URL — which terminates the inner jar with {@code "!/"}, e.g.
     * {@code jar:nested:/opt/app.jar/!BOOT-INF/lib/bcprov-jdk18on-1.80.jar!/} — as well as a plain
     * {@code file:.../bcprov-jdk18on-1.80.jar}. (The earlier form was the bug: leaving the trailing {@code "!/"}
     * on made the parsed file name empty, so every jar lacking an {@code Implementation-Version} — BouncyCastle,
     * Prometheus — silently dropped out of the packaged jar while resolving fine in exploded test runs.) Returns
     * {@code null} for an exploded class directory or a name with no {@code -<version>} suffix. Package-private
     * for testing.
     */
    static String versionFromJarName(String location) {
        if (location == null) {
            return null;
        }
        String path = location;
        // A nested-jar URL ends with "!/" (or "!"); trim trailing separators so the jar file name comes last.
        while (path.endsWith("/") || path.endsWith("!")) {
            path = path.substring(0, path.length() - 1);
        }
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('!'));
        String file = cut >= 0 ? path.substring(cut + 1) : path;
        if (file.endsWith(".jar")) {
            file = file.substring(0, file.length() - 4);
        }
        Matcher m = JAR_VERSION.matcher(file);
        return m.find() ? m.group(1) : null;
    }

    /**
     * A version read from a small UTF-8 classpath text resource — for a bundled asset (a JS bundle like
     * CodeMirror) that has no class to introspect. Returns {@code null} if the resource is absent or blank.
     */
    private static String classpathVersion(String resource) {
        try (var in = RuntimeInfoService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return v.isBlank() ? null : v;
        } catch (Exception unreadable) {
            return null;
        }
    }
}
