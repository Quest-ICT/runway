package mx.quest.runway.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Log levels set from the console (Activity → Logs → Log levels): applied at once through Spring Boot's
 * {@link LoggingSystem}, kept in {@code platform.yml} ({@link PlatformSettings#logLevels}) and applied again when the
 * server starts, so a level chosen to diagnose a problem survives a restart until it is set back.
 *
 * <p><b>The server's value wins.</b> A {@code logging.level.<name>} set in the host's {@code application.yml}, an
 * environment variable or an argument ({@link HostSettings}) is never overridden: the console shows it locked, saves
 * nothing for it, and skips it at startup. Setting a logger back to <i>Default</i> restores what the configuration
 * says ({@code logging.level.<name>} in the shipped {@code application.yml}, else inherited from its parent; the root
 * logger has no parent and goes back to {@code logging.level.root}, else INFO).
 *
 * <p>Raising a level to {@code DEBUG} or {@code TRACE} can write request details to the log, so this is a platform
 * administrator's operation and every change is recorded by the caller in the activity log.
 */
@Service
public class LogLevelService {

    private static final Logger log = LoggerFactory.getLogger(LogLevelService.class);

    /** The loggers the screen always offers, most useful first. */
    public static final List<String> COMMON = List.of(
            "mx.quest.runway", "runway.access", "runway.protect", "mx.quest.runway.web", "mx.quest.runway.security",
            "mx.quest.runway.plugin", "mx.quest.runway.backup", "mx.quest.runway.cluster", "mx.quest",
            "org.springframework.security", "org.springframework.web", "org.eclipse.jetty", "com.hazelcast", "ROOT");

    /** The levels the console offers; {@code null}/blank means Default. */
    public static final List<String> LEVELS = List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /** A logger name: dot-separated Java identifiers, or {@code ROOT}. */
    private static final Pattern NAME = Pattern.compile("ROOT|[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final int MAX_NAME = 200;

    private final LoggingSystem logging;
    private final PlatformSettingsStore platform;
    private final HostSettings host;
    private final Environment environment;

    public LogLevelService(LoggingSystem logging, PlatformSettingsStore platform, HostSettings host,
                           Environment environment) {
        this.logging = logging;
        this.platform = platform;
        this.host = host;
        this.environment = environment;
    }

    /** One logger as the screen shows it. */
    public record Row(String name, String effective, String chosen, String lockedBy) {

        /** Whether the screen has a sentence for this logger: only the common ones do. */
        public boolean described() {
            return COMMON.contains(name);
        }
    }

    /** Thrown for a name or level the rules refuse; {@code code} names the rule for the console's message. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public Invalid(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The common loggers plus every logger with a level stored here, each with its effective level now. */
    public List<Row> rows() {
        Map<String, String> stored = platform.get().logLevels();
        List<String> names = new ArrayList<>(COMMON);
        stored.keySet().stream().filter(name -> !names.contains(name)).sorted().forEach(names::add);
        List<Row> out = new ArrayList<>();
        for (String name : names) {
            LoggerConfiguration config = logging.getLoggerConfiguration(name);
            String effective = config == null || config.getEffectiveLevel() == null ? ""
                    : config.getEffectiveLevel().name();
            out.add(new Row(name, effective, stored.get(name), hostOrigin(name).orElse(null)));
        }
        return out;
    }

    /**
     * Sets {@code name} to {@code level} now and keeps it ({@code null}/blank: back to Default).
     *
     * @throws Invalid for a malformed name, an unknown level, or a logger the server sets
     */
    public synchronized void set(String name, String level) {
        String typed = name == null ? "" : name.strip();
        if (typed.length() > MAX_NAME || !NAME.matcher(typed).matches()) {
            throw new Invalid("badName", "not a logger name: " + name);
        }
        // "root" in any case is the root logger for Logback; keep one spelling so Default can find and remove it.
        String logger = ROOT_NAME.equalsIgnoreCase(typed) ? ROOT_NAME : typed;
        Optional<String> locked = hostOrigin(logger);
        if (locked.isPresent()) {
            throw new Invalid("locked", logger + " is set on the server in " + locked.get());
        }
        String chosen = level == null || level.isBlank() ? null : level.strip().toUpperCase(Locale.ROOT);
        if (chosen != null && !LEVELS.contains(chosen)) {
            throw new Invalid("badLevel", "not a log level: " + level);
        }
        // Applied first: if the logging system refuses, nothing is stored and the screen still tells the truth.
        // If storing then fails, the previous level is put back, so no unrecorded DEBUG or TRACE stays live.
        String previous = storedLevel(platform.get().logLevels(), logger);
        apply(logger, chosen);
        try {
            platform.update(current -> {
                Map<String, String> next = new TreeMap<>(current.logLevels());
                next.keySet().removeIf(key -> sameLogger(key, logger));
                if (chosen != null) {
                    next.put(logger, chosen);
                }
                return current.withLogLevels(next);
            });
        } catch (RuntimeException notStored) {
            apply(logger, previous);
            throw notStored;
        }
    }

    /** The level stored for {@code logger} (the root logger under any spelling), or null. */
    private static String storedLevel(Map<String, String> stored, String logger) {
        return stored.entrySet().stream().filter(e -> sameLogger(e.getKey(), logger)).map(Map.Entry::getValue)
                .filter(LEVELS::contains).findFirst().orElse(null);
    }

    private static boolean sameLogger(String key, String logger) {
        return ROOT_NAME.equals(logger) ? ROOT_NAME.equalsIgnoreCase(key) : logger.equals(key);
    }

    /** Applies the stored levels once the server is up; a logger the server sets keeps the server's level. */
    @EventListener(ApplicationReadyEvent.class)
    public void applyStored() {
        Map<String, String> applied = new LinkedHashMap<>();
        Map<String, String> stored = platform.get().logLevels();
        platform.get().logLevels().forEach((name, level) -> {
            if (level == null || hostOrigin(name).isPresent() || !LEVELS.contains(level)) {
                return;
            }
            // A hand-written "root" or "Root" is the root logger too; the console's own spelling wins over it.
            if (!ROOT_NAME.equals(name) && ROOT_NAME.equalsIgnoreCase(name) && stored.containsKey(ROOT_NAME)) {
                return;
            }
            apply(name, level);
            applied.put(name, level);
        });
        if (!applied.isEmpty()) {
            log.info("applied log levels set in the console: {}", applied);
        }
    }

    private void apply(String name, String level) {
        LogLevel target = level != null ? LogLevel.valueOf(level) : configured(name);
        boolean root = ROOT_NAME.equalsIgnoreCase(name);
        if (root && target == null) {
            target = configured(ROOT_NAME);
        }
        logging.setLogLevel(root ? LoggingSystem.ROOT_LOGGER_NAME : name, target);
    }

    /**
     * The level the configuration gives {@code name} ({@code logging.level.<name>}), or null to inherit. The root
     * logger cannot inherit: it is read from {@code logging.level.root} (the configuration's spelling; the lookup is
     * case-sensitive) and falls back to {@link #ROOT_DEFAULT}, Spring Boot's own root level.
     */
    LogLevel configured(String name) {
        boolean root = ROOT_NAME.equals(name);
        String value = environment.getProperty("logging.level." + (root ? "root" : name));
        LogLevel parsed = null;
        if (value != null && !value.isBlank()) {
            try {
                parsed = LogLevel.valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                parsed = null;
            }
        }
        return parsed == null && root ? ROOT_DEFAULT : parsed;
    }

    /** The root logger's level when the configuration names none: Spring Boot's default. */
    static final LogLevel ROOT_DEFAULT = LogLevel.INFO;

    private Optional<String> hostOrigin(String name) {
        return host.origin("logging.level." + name);
    }

    private static final String ROOT_NAME = "ROOT";
}
