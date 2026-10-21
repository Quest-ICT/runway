package mx.quest.runway.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSettingsService.RenderedSetting;
import mx.quest.runway.plugin.SettingType;
import mx.quest.runway.plugin.SettingValidationException;
import mx.quest.runway.web.CidrSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * Reads and saves the console-managed kernel settings ({@link KernelSettings}) for one screen.
 *
 * <ul>
 *   <li><b>One home.</b> Values go to {@code settings:} in {@code external/platform.yml}. A key the host sets
 *       ({@link HostSettings}) is never written by a save and keeps whatever is stored for it: the host's value wins.</li>
 *   <li><b>Only differences are stored.</b> A value equal to its declared default is removed, so a later release
 *       that changes a default is not pinned to the old one by a page someone once saved.</li>
 *   <li><b>A save cannot stop the server from starting.</b> Each value is checked as the binder will read it
 *       (ranges, durations, sizes, cron, CIDRs, language codes, Jetty's min ≤ max) before anything is written.</li>
 *   <li><b>Pending.</b> Values bind at startup; {@link #pendingKeys} lists what was saved since this process
 *       started.</li>
 *   <li><b>Handing a key over.</b> {@link #adopt} copies the value the host sets into the console's store, so the
 *       operator can remove the host's line and restart once with nothing changing; {@link #lockStates} says, for
 *       each locked field, what removing the host's line would do today.</li>
 * </ul>
 */
@Service
public class KernelSettingsService {

    private static final Logger log = LoggerFactory.getLogger(KernelSettingsService.class);

    private final PlatformSettingsStore platform;
    private final HostSettings host;
    private final Environment environment;
    private final SecretCipher cipher;
    /** What was stored when this process started: the values it is running with. */
    private final Map<String, String> atStartup;

    public KernelSettingsService(PlatformSettingsStore platform, HostSettings host, Environment environment,
                                 SecretCipher cipher) {
        this.platform = platform;
        this.host = host;
        this.environment = environment;
        this.cipher = cipher;
        this.atStartup = Map.copyOf(platform.get().settings());
    }

    /**
     * The current value of each setting of {@code screen}, for the form: the stored value, else the value the server
     * runs with for a host-set key, else the declared default. A secret is never echoed; only whether one is set.
     */
    public List<RenderedSetting> render(String screen) {
        Map<String, String> stored = platform.get().settings();
        List<RenderedSetting> out = new ArrayList<>();
        for (PluginSettingDef def : KernelSettings.forScreen(screen)) {
            boolean secret = SettingType.of(def.type()) == SettingType.SECRET;
            String hostValue = host.setOnHost(def.key()) ? live(def.key()) : null;
            String value = hostValue != null ? hostValue : stored.get(def.key());
            if (secret) {
                out.add(new RenderedSetting(def, "", value != null && !value.isBlank()));
            } else {
                out.add(new RenderedSetting(def, value != null ? value : text(def.defaultValue()), false));
            }
        }
        return out;
    }

    /** For each host-set key of {@code screen}, where the host sets it. */
    public Map<String, String> locks(String screen) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PluginSettingDef def : KernelSettings.forScreen(screen)) {
            host.origin(def.key()).ifPresent(origin -> out.put(def.key(), origin));
        }
        return out;
    }

    /**
     * What removing the host's line would do, for a field the host sets: the console's own value (or, without one,
     * the default) is what binds then. Shown on the locked row so the operator knows before editing the host.
     */
    public enum LockState {
        /** The console stores the same value as the host's: removing the line changes nothing. */
        SAME,
        /** The console stores nothing and the default is the host's value: removing the line changes nothing. */
        DEFAULT,
        /** The console stores a different value, which takes effect once the line is removed. */
        DIFFERS,
        /** The console stores nothing and the default differs from the host's value, so the default takes effect. */
        NONE;

        /** The state's name as the message keys spell it ({@code op.settings.locked.same}, …). */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * For each host-set key of {@code screen}, what removing the host's line would do ({@link LockState}). A secret
     * is compared after decrypting the stored one; neither value leaves this method.
     */
    public Map<String, LockState> lockStates(String screen) {
        Map<String, String> stored = platform.get().settings();
        Map<String, LockState> out = new LinkedHashMap<>();
        for (PluginSettingDef def : KernelSettings.forScreen(screen)) {
            if (!host.setOnHost(def.key())) {
                continue;
            }
            SettingType type = SettingType.of(def.type());
            String hostValue = canonical(def, type, live(def.key()));
            String console = storedPlaintext(type, stored.get(def.key()));
            if (console != null) {
                out.put(def.key(), hostValue.equals(canonical(def, type, console)) ? LockState.SAME
                        : LockState.DIFFERS);
            } else {
                out.put(def.key(), hostValue.equals(canonical(def, type, text(def.defaultValue())))
                        ? LockState.DEFAULT : LockState.NONE);
            }
        }
        return out;
    }

    /**
     * The stored value as it would bind: a secret decrypted; {@code null} when nothing is stored, or when a stored
     * secret cannot be read (no secret key, or a wrong one), because the server then starts without it, as the
     * initializer does.
     */
    private String storedPlaintext(SettingType type, String stored) {
        if (stored == null || type != SettingType.SECRET || !cipher.isEncrypted(stored)) {
            return stored;
        }
        if (!cipher.isAvailable()) {
            return null;
        }
        try {
            return cipher.decrypt(stored);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * {@code value} in the form a save would store it, for comparing two values. A blank stays blank (the host's
     * blank means blank, not the default), and a value the type refuses is compared as typed.
     */
    private static String canonical(PluginSettingDef def, SettingType type, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (type == SettingType.SECRET) {
            return value.trim();
        }
        try {
            Object coerced = type.coerce(def, enumChoice(def, type, value.trim()));
            return coerced instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList())
                    : (coerced == null ? "" : String.valueOf(coerced));
        } catch (RuntimeException refused) {
            return value.trim();
        }
    }

    /**
     * An enum value spelled as the declared choice it matches ignoring case ({@code ENFORCE} → {@code enforce}): the
     * binder reads an enum in any case, so the host's {@code ENFORCE} runs as {@code enforce}. Any other value is
     * returned as it is, and the type check then refuses it.
     */
    private static String enumChoice(PluginSettingDef def, SettingType type, String value) {
        if (type != SettingType.ENUM) {
            return value;
        }
        for (String choice : SettingType.allowedValues(def.range())) {
            if (choice.equalsIgnoreCase(value)) {
                return choice;
            }
        }
        return value;
    }

    /** What {@link #adopt} did. */
    public enum Adopted {
        /** The console now stores the host's value. */
        STORED,
        /** The host's value is the default (for a token: none), so the console stores nothing for the key. */
        DEFAULT
    }

    /**
     * Copies the value the server runs with for a host-set {@code key} (what the locked row shows) into the console's
     * store, so the operator can remove the host's line and restart once, with no change in behaviour and no start on
     * the default in between. The host's value keeps winning until then; the field stays locked.
     *
     * <p>The value goes through the checks a save applies: no <code>${</code> ({@link PlaceholderRefused}), its type
     * and range, the per-key checks (a catch-all trusted proxy, say, which the host may set but the console refuses)
     * and the cross-checks, which here read the key as it will bind once the host's line is gone. Only a difference
     * from the default is stored. A token is stored encrypted and needs the secret key; a token the host sets blank
     * removes the console's stored one, since blank is what runs. A blank value the console cannot store (a blank
     * field here means the default, and the default is not blank) is refused.
     *
     * @return whether a value is now stored, or none was needed
     * @throws NotSetOnHost when {@code key} is not a kernel setting the host sets (nothing to copy)
     * @throws SettingValidationException naming {@code key} when the value is refused; nothing is written then
     */
    public synchronized Adopted adopt(String key) {
        PluginSettingDef def = KernelSettings.find(key).orElseThrow(() -> new NotSetOnHost(key));
        if (!host.setOnHost(key)) {
            throw new NotSetOnHost(key);
        }
        String value = live(key);
        PlatformSettings saved = platform.update(current -> current.withSettings(
                adopted(def, value == null ? "" : value, current.settings())));
        log.info("copied the server's value of {} into the console's settings (it binds once the host's line is "
                + "removed)", key);
        return saved.settings().containsKey(key) ? Adopted.STORED : Adopted.DEFAULT;
    }

    /** {@code stored} with the host's {@code value} of {@code def} applied, checked as a save checks it. */
    private Map<String, String> adopted(PluginSettingDef def, String value, Map<String, String> stored) {
        if (value.contains(PLACEHOLDER)) {
            throw new PlaceholderRefused(def.key());
        }
        SettingType type = SettingType.of(def.type());
        Map<String, String> next = new LinkedHashMap<>(stored);
        if (value.isBlank()) {
            if (type != SettingType.SECRET && !normalizedDefault(def, type).isEmpty()) {
                throw new SettingValidationException(def.key(),
                        "the server sets this to blank, which this page cannot store: a blank field here means "
                                + "the default", "op.config.adopt.blank");
            }
            next.remove(def.key());
        } else {
            apply(def, type, enumChoice(def, type, value.trim()), next);
        }
        crossCheck(next, def.key());
        return next;
    }

    /** The host does not set the key, or it is not a kernel setting: there is no server value to copy. */
    public static final class NotSetOnHost extends SettingValidationException {
        public NotSetOnHost(String key) {
            super(key, "the server does not set this value, so there is nothing to copy",
                    "op.config.adopt.notOnHost");
        }
    }

    /** The keys of {@code screen} saved since this process started, which apply after a restart. */
    public List<String> pendingKeys(String screen) {
        Map<String, String> stored = platform.get().settings();
        List<String> out = new ArrayList<>();
        for (PluginSettingDef def : KernelSettings.forScreen(screen)) {
            if (!Objects.equals(stored.get(def.key()), atStartup.get(def.key()))) {
                out.add(def.key());
            }
        }
        return out;
    }

    /**
     * Validates and saves {@code submitted} for {@code screen}. Host-set keys are skipped; a blank value restores the
     * default (removes the key); a blank secret keeps the stored one. A value containing <code>${</code> is refused
     * ({@link PlaceholderRefused}): the stored text is bound by Spring, which would replace a {@code ${...}} with
     * another property, such as the master key, and send it out in a public header.
     *
     * <p>One atomic change of {@code platform.yml} ({@link PlatformSettingsStore#update}): the values are merged into
     * the latest stored settings, so a save made at the same moment on another screen is not lost.
     *
     * @throws SettingValidationException naming the first invalid key; nothing is written then
     */
    public synchronized void save(String screen, Map<String, String> submitted) {
        PlatformSettings saved = platform.update(current -> current.withSettings(
                merged(screen, submitted, current.settings())));
        log.info("saved {} console-managed kernel setting(s) of '{}' (applies on restart)", saved.settings().size(),
                screen);
    }

    /** {@code stored} with the submitted values of {@code screen} applied, all checked; throws before any change. */
    private Map<String, String> merged(String screen, Map<String, String> submitted, Map<String, String> stored) {
        Map<String, String> next = new LinkedHashMap<>(stored);
        for (PluginSettingDef def : KernelSettings.forScreen(screen)) {
            if (host.setOnHost(def.key())) {
                continue; // the host's value wins; the console neither writes nor clears it
            }
            String raw = submitted == null ? null : submitted.get(def.key());
            if (raw != null && raw.contains(PLACEHOLDER)) {
                throw new PlaceholderRefused(def.key());
            }
            apply(def, SettingType.of(def.type()), raw, next);
        }
        crossCheck(next, null);
        return next;
    }

    /**
     * One value applied to {@code next} as a save applies it: a secret encrypted (a blank one keeps what is stored);
     * any other value coerced to its type and range, put through its per-key check, and stored only when it differs
     * from the default (a blank one restores the default). The caller has refused <code>${</code> already.
     *
     * @throws SettingValidationException naming the key when the value is refused; {@code next} is then unchanged
     */
    private void apply(PluginSettingDef def, SettingType type, String raw, Map<String, String> next) {
        if (type == SettingType.SECRET) {
            if (raw != null && !raw.isBlank()) {
                if (!cipher.isAvailable()) {
                    throw new SettingValidationException(def.key(),
                            "set runway.security.secret-key on the server to store a token here",
                            "op.config.invalid.noSecretKey");
                }
                next.put(def.key(), cipher.encrypt(raw.trim()));
            }
            return;
        }
        String value = raw == null || raw.isBlank() ? text(def.defaultValue()) : raw.trim();
        Object coerced = type.coerce(def, value == null ? "" : value);
        String normalized = coerced instanceof List<?> list
                ? String.join(",", list.stream().map(String::valueOf).toList())
                : (coerced == null ? "" : String.valueOf(coerced));
        check(def.key(), normalized);
        if (normalized.equals(normalizedDefault(def, type))) {
            next.remove(def.key());
        } else {
            next.put(def.key(), normalized);
        }
    }

    /** The start of a Spring placeholder, which a console value may not contain. */
    static final String PLACEHOLDER = "${";

    /**
     * A submitted value contained <code>${</code>. Its own type so the form can say why in the operator's language; the
     * message names the key only, never the value.
     */
    public static final class PlaceholderRefused extends SettingValidationException {
        public PlaceholderRefused(String key) {
            super(key, "a value cannot contain '${': the server would fill it in from its own configuration");
        }
    }

    /**
     * The value {@code key} will have after the next restart: the host's when it sets it, else the stored one, else
     * the declared default. For notes that compare a saved limit with what sites chose.
     */
    public String upcoming(String key) {
        return effective(key, platform.get().settings(), null);
    }

    /**
     * The value the server runs with for a host-set {@code key}, read the way the binder reads it. For a list that
     * matters: a host's YAML list has indexed keys only ({@code key[0]}, {@code key[1]}), and a flat lookup across
     * every source would find a comma string the console once stored, ranked below the host, and show that instead.
     * The binder takes the first source that holds the list, which is the host's.
     */
    private String live(String key) {
        boolean list = KernelSettings.find(key).map(def -> SettingType.of(def.type()) == SettingType.LIST)
                .orElse(false);
        if (list) {
            Binder binder = Binder.get(environment);
            try {
                List<String> items = binder.bind(key, Bindable.listOf(String.class)).orElse(null);
                if (items != null) {
                    return String.join(",", items);
                }
            } catch (BindException gap) {
                // A host list with a hole in its numbering (key[0] and key[2], no key[1]) does not bind. Today the
                // server would not have started with one (the list keys are bound at startup), but this screen must
                // never be a 500 for it: show the field locked with the items that can be read, and log why.
                log.warn("{} is set on the server as a list with a gap in its numbering, which does not bind; the form "
                        + "shows the items it can read", key);
                List<String> readable = new ArrayList<>();
                for (int i = 0; i < MAX_LIST_ITEMS; i++) {
                    binder.bind(key + "[" + i + "]", String.class).ifBound(readable::add);
                }
                if (!readable.isEmpty()) {
                    return String.join(",", readable);
                }
            }
        }
        return environment.getProperty(key);
    }

    /** How many indexes of a host list with a gap {@link #live} looks at. A real list has a handful of items. */
    private static final int MAX_LIST_ITEMS = 256;

    private static String normalizedDefault(PluginSettingDef def, SettingType type) {
        String text = text(def.defaultValue());
        if (text == null) {
            return "";
        }
        Object coerced = type.coerce(def, text);
        return coerced instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList())
                : (coerced == null ? "" : String.valueOf(coerced));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * A check that a value passes beyond its type and range: the console message for a value that fails it (its
     * argument is the value), the English default, and the test, which throws on a bad value. A test may throw a more
     * precise {@link SettingValidationException} of its own, whose code and English then win.
     */
    private record Check(String code, String english, Consumer<String> test) {
    }

    /** The checks a value must pass beyond its type and range, so it binds and starts. */
    private static final Map<String, Check> CHECKS = Map.of(
            "server.servlet.session.timeout", new Check("op.config.invalid.duration",
                    "is not a length of time, such as 30m or 2h", v -> DurationStyle.detectAndParse(v)),
            "spring.servlet.multipart.max-file-size", new Check("op.config.invalid.size",
                    "is not a size, such as 10MB or 512KB", v -> DataSize.parse(v)),
            "spring.servlet.multipart.max-request-size", new Check("op.config.invalid.size",
                    "is not a size, such as 10MB or 512KB", v -> DataSize.parse(v)),
            "runway.backups.schedule.cron", new Check("op.config.invalid.cron",
                    "is not a schedule with six fields, such as 0 0 3 * * *", v -> CronExpression.parse(v)),
            "runway.proxy.trusted-cidrs", new Check("op.config.invalid.cidr",
                    "is not a list of IP addresses or CIDR ranges", v -> {
                        CidrSet.Parsed parsed = CidrSet.parse(split(v));
                        if (!parsed.rejected().isEmpty()) {
                            String bad = String.join(", ", parsed.rejected());
                            throw new SettingValidationException("runway.proxy.trusted-cidrs",
                                    "not an IP address or CIDR range: " + bad, "op.config.invalid.cidr", bad);
                        }
                        // A /0, or ranges that add up to one, has no legitimate meaning here: it trusts every client
                        // of that family to name its own address.
                        if (parsed.set().hasCatchAll()) {
                            throw new SettingValidationException("runway.proxy.trusted-cidrs",
                                    "these addresses cover every IPv4 or every IPv6 address, so every client could "
                                            + "fake its address; list only your proxy's address",
                                    "op.config.invalid.cidrCatchAll");
                        }
                    }),
            "runway.operator.ui-languages", new Check("op.config.invalid.language",
                    "is not a language code such as en or es",
                    v -> split(v).forEach(code -> requireLanguage("runway.operator.ui-languages", code))),
            "runway.operator.default-language", new Check("op.config.invalid.language",
                    "is not a language code such as en or es",
                    v -> requireLanguage("runway.operator.default-language", v)));

    private static void check(String key, String value) {
        Check check = CHECKS.get(key);
        if (check == null || value.isBlank()) {
            return;
        }
        try {
            check.test().accept(value);
        } catch (SettingValidationException precise) {
            throw precise;
        } catch (RuntimeException invalid) {
            throw new SettingValidationException(key, "'" + value + "' " + check.english(), check.code(), value);
        }
    }

    /**
     * The checks across keys. {@code handedOver} is a host-set key being copied into the console ({@link #adopt}): it
     * is read from {@code next}, as it will bind once the host's line is removed; {@code null} on a save.
     */
    private void crossCheck(Map<String, String> next, String handedOver) {
        long min = effectiveLong("server.jetty.threads.min", next, handedOver);
        long max = effectiveLong("server.jetty.threads.max", next, handedOver);
        if (min > max) {
            throw new SettingValidationException("server.jetty.threads.min",
                    "the fewest request threads (" + min + ") is above the most (" + max + ")",
                    "op.config.invalid.threads", String.valueOf(min), String.valueOf(max));
        }
        String language = effective("runway.operator.default-language", next, handedOver);
        String offered = effective("runway.operator.ui-languages", next, handedOver);
        if (language != null && offered != null && !split(offered).contains(language)) {
            throw new SettingValidationException("runway.operator.default-language",
                    "'" + language + "' is not one of the languages offered (" + offered + ")",
                    "op.config.invalid.languageNotOffered", language, offered);
        }
    }

    private String effective(String key, Map<String, String> next, String handedOver) {
        if (host.setOnHost(key) && !key.equals(handedOver)) {
            return live(key);
        }
        String stored = next.get(key);
        return stored != null ? stored : KernelSettings.find(key).map(d -> text(d.defaultValue())).orElse(null);
    }

    private long effectiveLong(String key, Map<String, String> next, String handedOver) {
        String value = effective(key, next, handedOver);
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split("[,\\n]")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static void requireLanguage(String key, String code) {
        Locale locale = Locale.forLanguageTag(code);
        // Two or three letters for the language itself (ISO 639): Java also accepts 5-8 letters ("english").
        if (locale.getLanguage().length() < 2 || locale.getLanguage().length() > 3
                || !locale.toLanguageTag().equalsIgnoreCase(code)) {
            throw new SettingValidationException(key, "'" + code + "' is not a language code such as en or es",
                    "op.config.invalid.language", code);
        }
    }
}
