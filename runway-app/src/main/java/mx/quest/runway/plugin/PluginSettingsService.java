package mx.quest.runway.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.OwnerOnlyFiles;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads, validates and writes the <b>app-level</b> plugin settings file
 * ({@code external/plugin-settings/<id>.yml}) generically from a plugin's declared {@code settings:} schema —
 * the plugin never sees the form. The file is loaded as a property source <b>above</b> the config at boot
 * (see {@code PluginContextInitializer}) and bound by the plugin's own {@code @ConfigurationProperties}; every
 * key sits under the {@code runway.plugin.<id>} prefix, so the schema key is the prefix <em>tail</em>.
 *
 * <p>Locked behaviours: values are typed-and-validated per {@link PluginSettingDef#type()} before anything is
 * written; <b>secret</b> values are stored encrypted ({@code ENC(...)} via {@link SecretCipher}) and the file
 * is {@code chmod 600} whenever it holds one; a blank <b>secret</b> field on resubmit <b>keeps</b> the existing
 * secret (you never have to retype it); and changes apply on the <b>next restart</b> (this writes the file, it
 * does not touch the live {@code Environment}) — {@link #pendingRestartKeys} surfaces that drift.
 */
@Service
public class PluginSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PluginSettingsService.class);
    private static final String PREFIX = "runway.plugin.";

    private final StorageLayout layout;
    private final SecretCipher cipher;

    public PluginSettingsService(StorageLayout layout, SecretCipher cipher) {
        this.layout = layout;
        this.cipher = cipher;
    }

    // ---- read for the form ------------------------------------------------------------------------------

    /**
     * The current value of every schema setting, for rendering the form. A <b>secret</b> is never echoed —
     * {@link RenderedSetting#value()} is blank and {@link RenderedSetting#secretSet()} says whether one is
     * stored (so the form shows "leave blank to keep"). Non-secret values come from the file, else the
     * declared default.
     */
    public List<RenderedSetting> render(String pluginId, List<PluginSettingDef> schema) {
        Map<String, String> stored = readSubtree(pluginId);
        List<RenderedSetting> out = new ArrayList<>(schema.size());
        for (PluginSettingDef def : schema) {
            boolean secret = SettingType.of(def.type()) == SettingType.SECRET;
            String storedValue = stored.get(def.key());
            if (secret) {
                out.add(new RenderedSetting(def, "", storedValue != null && !storedValue.isBlank()));
            } else {
                String value = storedValue != null ? storedValue : asString(def.defaultValue());
                out.add(new RenderedSetting(def, value == null ? "" : value, false));
            }
        }
        return out;
    }

    // ---- save -------------------------------------------------------------------------------------------

    /**
     * Validates and writes every schema key (the complete key set, so the file masks any stray same-prefix
     * key), coercing each submitted value by type. A blank/absent non-secret falls back to its default; a
     * blank secret keeps the stored one. The file is written atomically and {@code chmod 600} if it holds any
     * secret. Throws {@link SettingValidationException} (a field error, never a 500) on a bad value, after
     * which nothing is written.
     *
     * <p>A blank secret is kept only while every setting it is {@linkplain PluginSettingDef#secretBoundTo() bound
     * to} keeps its value; when one changes, the stored secret is left out of the write ({@link SecretBindings}).
     *
     * @return the keys of the stored secrets this save removed because a bound setting changed (empty when none)
     */
    public List<String> save(String pluginId, List<PluginSettingDef> schema, Map<String, String> submitted) {
        Map<String, String> existing = readSubtree(pluginId);
        Map<String, Object> resolved = new LinkedHashMap<>();
        boolean anySecret = false;

        for (PluginSettingDef def : schema) {
            SettingType type = SettingType.of(def.type());
            String raw = submitted == null ? null : submitted.get(def.key());
            if (type == SettingType.SECRET) {
                String value = resolveSecret(def, raw, existing.get(def.key()));
                if (value != null) {
                    resolved.put(def.key(), value);
                    anySecret = true;
                }
                continue;
            }
            // App-level blank policy: a blank/absent value falls back to the declared default.
            String resolvedValue = (raw == null || raw.isBlank()) ? asString(def.defaultValue()) : raw;
            Object value = type.coerce(def, resolvedValue); // throws SettingValidationException on a bad value
            if (value != null) {
                resolved.put(def.key(), value);
            }
        }
        List<String> dropped = SecretBindings.toDrop(schema, submitted, existing::get, resolved::get);
        dropped.forEach(resolved::remove);
        writeFile(pluginId, resolved, anySecret); // owner-only even if a dropped secret was the only one: harmless
        if (!dropped.isEmpty()) {
            log.info("plugin '{}': removed the stored app-level secret(s) {} — a setting they are bound to changed "
                    + "and no new value was entered", pluginId, dropped);
        }
        log.info("saved app-level settings for plugin '{}' ({} keys; applies on restart)", pluginId, resolved.size());
        return dropped;
    }

    /** Encrypts a non-blank secret; a blank field keeps the stored value; nothing stored stays absent. */
    private String resolveSecret(PluginSettingDef def, String raw, String existing) {
        if (raw != null && !raw.isBlank()) {
            if (!cipher.isAvailable()) {
                throw new SettingValidationException(def.key(),
                        "cannot store a secret: no encryption key is configured (runway.security.secret-key)");
            }
            return cipher.encrypt(raw);
        }
        return existing; // blank on resubmit → keep what's stored (possibly null)
    }

    // ---- drift ------------------------------------------------------------------------------------------

    /** Keys present in the file under this plugin's prefix that the schema does not declare (a drift warning). */
    public List<String> strayKeys(String pluginId, List<PluginSettingDef> schema) {
        Set<String> declared = new java.util.HashSet<>();
        schema.forEach(def -> declared.add(def.key()));
        List<String> strays = new ArrayList<>();
        for (String key : new TreeSet<>(readSubtree(pluginId).keySet())) {
            if (!declared.contains(key)) {
                strays.add(key);
            }
        }
        return List.copyOf(strays);
    }

    /**
     * The schema keys whose saved file value differs from what the live application currently sees — the
     * "saved, pending restart" set (and any key an external override masks). {@code liveValue} maps a full
     * property key ({@code runway.plugin.<id>.<key>}) to the value the live {@code Environment} resolves, or
     * {@code null}. Secrets are compared by their stored {@code ENC(...)} form, never decrypted.
     */
    public List<String> pendingRestartKeys(String pluginId, List<PluginSettingDef> schema,
                                           Function<String, String> liveValue) {
        Map<String, String> stored = readSubtree(pluginId);
        List<String> pending = new ArrayList<>();
        for (PluginSettingDef def : schema) {
            String saved = stored.get(def.key());
            String live = liveValue.apply(PREFIX + pluginId + "." + def.key());
            if (!java.util.Objects.equals(normalize(saved), normalize(live))) {
                pending.add(def.key());
            }
        }
        return List.copyOf(pending);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ---- file I/O (nested YAML under runway.plugin.<id>) ------------------------------------------------

    /** Flat map of {@code <settingKey> → stored string value} read from the file (empty if absent/unreadable). */
    @SuppressWarnings("unchecked")
    private Map<String, String> readSubtree(String pluginId) {
        Path file = layout.pluginSettingsFile(pluginId); // traversal-safe
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = Yaml.read(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
        if (root == null) {
            return Map.of();
        }
        // The plugin id is a SINGLE map key under runway.plugin (never split on dots); the setting key below it
        // may itself be dotted and nests normally.
        Object subtree = navigate(root, List.of("runway", "plugin", pluginId));
        Map<String, String> flat = new LinkedHashMap<>();
        if (subtree instanceof Map<?, ?> map) {
            flatten("", (Map<String, Object>) map, flat);
        }
        return flat;
    }

    private void writeFile(String pluginId, Map<String, Object> settings, boolean secretBearing) {
        Path file = layout.pluginSettingsFile(pluginId);
        // Nest only the runway.plugin.<id> prefix; the setting keys are written as LITERAL map keys (a dotted
        // key like "rate-limit.capacity" stays one key), so two keys where one is a prefix of the other never
        // collide in the tree (which would ClassCast/500 or silently drop a sibling). Spring's relaxed binding
        // still resolves the flattened property name either way.
        Map<String, Object> root = new LinkedHashMap<>();
        putNested(root, List.of("runway", "plugin", pluginId), new LinkedHashMap<>(settings));
        try {
            mkdirs(file.getParent());
            AtomicFiles.write(file, out -> Yaml.write(out, root));
            if (secretBearing) {
                restrictToOwner(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + file, e);
        }
    }

    /**
     * Restricts a secret-bearing settings file to its owner, <b>fail-secure</b>: if neither POSIX permissions nor
     * an ACL can protect it, the just-written file is deleted and the save fails — a secret is never left on disk
     * with default (possibly world-readable) permissions.
     */
    private static void restrictToOwner(Path file) throws IOException {
        try {
            OwnerOnlyFiles.restrictToOwner(file);
        } catch (IOException cannotProtect) {
            Files.deleteIfExists(file);
            throw new IOException("refusing to persist a secret-bearing settings file without owner-only "
                    + "permissions: " + file, cannotProtect);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object navigate(Map<String, Object> root, List<String> path) {
        Object node = root;
        for (String segment : path) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = ((Map<String, Object>) map).get(segment);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> node, Map<String, String> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                flatten(key, (Map<String, Object>) child, out);
            } else if (value != null) {
                out.put(key, asString(value));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void putNested(Map<String, Object> root, List<String> path, Object value) {
        Map<String, Object> node = root;
        for (int i = 0; i < path.size() - 1; i++) {
            node = (Map<String, Object>) node.computeIfAbsent(path.get(i), k -> new LinkedHashMap<String, Object>());
        }
        node.put(path.get(path.size() - 1), value);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    private static void mkdirs(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    /** One setting rendered for the form: its definition, current (non-secret) value, and secret-set flag. */
    public record RenderedSetting(PluginSettingDef def, String value, boolean secretSet) {
    }
}
