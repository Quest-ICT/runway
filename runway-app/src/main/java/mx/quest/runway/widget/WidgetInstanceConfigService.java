package mx.quest.runway.widget;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSettingsService.RenderedSetting;
import mx.quest.runway.plugin.SettingType;
import mx.quest.runway.plugin.SettingValidationException;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.OwnerOnlyFiles;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The <b>per-instance</b> content-widget config engine (WIDGETS) — the tenant-scoped store the site manager's
 * <b>Content widgets</b> screen writes and {@link WidgetModelBuilder} reads. Where {@code SitePluginConfigService}
 * configures a plugin once for the whole tenancy, this configures each <em>placement</em>: the same widget type
 * anchored twice ({@code data-widget="carousel/home"} and {@code carousel/promos}) carries two independent config
 * maps, so one widget type renders differently per anchor.
 *
 * <p>All of a site's instance configs live in a single file, {@code sites/<id>/widget-instances.yml}, a map from
 * the anchor's {@code <type>/<instance>} key to that instance's flat config map. It sits <b>inside</b> the tenant
 * folder, so this plugin-GENERATED tenant data travels in the tenant backup (the self-containment rule). The
 * validated write reuses {@link SettingType} coercion, so widget settings and plugin settings never drift; secrets
 * are stored {@code ENC(...)} and the file is then restricted to its owner (fail-secure).
 *
 * <p>Because every instance shares one file, a save is a read-modify-write of the whole map; a per-site lock
 * serializes concurrent saves to the same site so two placements' saves cannot clobber each other. Reads are
 * cached per site and the cache is evicted on save (immediate apply), on a site update (a site reload applies a hand
 * edit of the file) and dropped wholesale when a site is deleted/restored. The page ETag folds the cached map
 * ({@link #siteConfigs}), so it moves exactly when a change applies.
 */
@Service
public class WidgetInstanceConfigService {

    private static final Logger log = LoggerFactory.getLogger(WidgetInstanceConfigService.class);

    private final StorageLayout layout;
    private final SecretCipher cipher;
    private final StoreCache<String, Map<String, Map<String, Object>>> cache = new StoreCache<>(256);
    /** Fixed stripe of monitors guarding each site's whole-file read-modify-write (bounded, hash-assigned). */
    private final Object[] siteLocks = createLocks();

    public WidgetInstanceConfigService(StorageLayout layout, SecretCipher cipher) {
        this.layout = layout;
        this.cipher = cipher;
    }

    /**
     * A site delete/restore rewrites that tenant's files wholesale, so drop the cache — no stale per-instance
     * config may survive a restore. (Mirrors {@code SitePluginConfigService}/{@code PageStore}.)
     */
    @org.springframework.context.event.EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            cache.invalidateAll();
        } else if (event.kind() == SiteChangedEvent.Kind.UPDATED) {
            // A site reload (or any site update) re-reads this site's file, like every other per-site config: a hand
            // edit of widget-instances.yml applies then — and the page ETag, which folds this map as loaded, moves
            // with it.
            cache.invalidate(event.siteId());
        }
    }

    /**
     * Every placement's config for {@code siteId} exactly as {@link #config} serves it — the cached instance, handed
     * out unchanged until a save, a site update or an eviction replaces it, so the page validator can fold it as
     * loaded (an identity-memoized digest) and move the ETag exactly when the change applies.
     *
     * @return {@code <type>/<instance>} → that placement's config (unmodifiable values; never {@code null})
     */
    public Map<String, Map<String, Object>> siteConfigs(String siteId) {
        return siteMap(siteId);
    }

    /**
     * The resolved config for one placed widget instance — what {@link WidgetModelBuilder} hands the widget as
     * {@code WidgetView.config()}. Returns only the explicitly-saved keys (an empty map when none), matching the
     * SPI contract that an absent key means "use the widget's default".
     *
     * @param siteId   the bound tenancy (traversal-validated by the storage layout)
     * @param type     the widget type ({@code <type>} half of the anchor)
     * @param instance the instance id ({@code <instance>} half of the anchor)
     * @return the stored config for this instance (cached; never {@code null}; empty when unconfigured)
     */
    public Map<String, Object> config(String siteId, String type, String instance) {
        Map<String, Object> stored = siteMap(siteId).get(instanceKey(type, instance));
        return stored == null ? Map.of() : stored;
    }

    /**
     * The current value of every schema key for this instance's editor form: a secret is never echoed (blank +
     * {@code secretSet}); a non-secret comes from the stored config, else the declared default.
     */
    public List<RenderedSetting> render(String siteId, String type, String instance, List<PluginSettingDef> schema) {
        Map<String, Object> stored = config(siteId, type, instance);
        List<RenderedSetting> out = new ArrayList<>(schema.size());
        for (PluginSettingDef def : schema) {
            Object raw = stored.get(def.key());
            if (SettingType.of(def.type()) == SettingType.SECRET) {
                out.add(new RenderedSetting(def, "", raw != null && !String.valueOf(raw).isBlank()));
            } else {
                String value = raw != null ? String.valueOf(raw) : asString(def.defaultValue());
                out.add(new RenderedSetting(def, value == null ? "" : value, false));
            }
        }
        return out;
    }

    /**
     * Validates and writes one instance's submitted values, <b>preserving unknown keys</b> within that instance
     * and every other instance's config, then evicts the site cache (immediate apply). Blank non-secret → key
     * removed (the widget default); a boolean equal to its default → removed; a secret blank → kept; a bad value
     * throws {@link SettingValidationException} and nothing is written.
     */
    public void save(String siteId, String type, String instance, List<PluginSettingDef> schema,
                     Map<String, String> submitted) {
        String key = instanceKey(type, instance);
        synchronized (lockFor(siteId)) {
            Map<String, Map<String, Object>> file = new LinkedHashMap<>(readFile(siteId)); // other instances kept
            Map<String, Object> merged = new LinkedHashMap<>(file.getOrDefault(key, Map.of())); // unknown keys kept
            for (PluginSettingDef def : schema) {
                applyOne(def, submitted == null ? null : submitted.get(def.key()), merged);
            }
            if (merged.isEmpty()) {
                file.remove(key); // an all-default instance leaves no entry behind
            } else {
                file.put(key, merged);
            }
            writeFile(siteId, file);
            cache.invalidate(siteId);
        }
        log.info("saved widget-instance config '{}' in site '{}' (applied immediately)", key, siteId);
    }

    /** Drops a site's cached instance configs (e.g. on a site delete/restore). */
    public void evict(String siteId) {
        cache.invalidate(siteId);
    }

    private Map<String, Map<String, Object>> siteMap(String siteId) {
        return cache.get(siteId, this::readFile);
    }

    private void applyOne(PluginSettingDef def, String raw, Map<String, Object> into) {
        SettingType type = SettingType.of(def.type());
        if (type == SettingType.SECRET) {
            if (raw != null && !raw.isBlank()) {
                if (!cipher.isAvailable()) {
                    throw new SettingValidationException(def.key(),
                            "cannot store a secret: no encryption key is configured (runway.security.secret-key)");
                }
                into.put(def.key(), cipher.encrypt(raw));
            } // blank → keep whatever is already in the merged map
            return;
        }
        if (type == SettingType.BOOLEAN) {
            boolean value = "true".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw) || "1".equals(raw);
            boolean dflt = isDefaultTrue(def);
            if (value == dflt) {
                into.remove(def.key());
            } else {
                into.put(def.key(), value);
            }
            return;
        }
        if (raw == null || raw.isBlank()) {
            into.remove(def.key()); // blank = remove = the widget default
            return;
        }
        into.put(def.key(), type.coerce(def, raw)); // throws SettingValidationException on a bad value
    }

    private static boolean isDefaultTrue(PluginSettingDef def) {
        Object dflt = def.defaultValue();
        return dflt != null && "true".equalsIgnoreCase(String.valueOf(dflt));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readFile(String siteId) {
        Path file = layout.widgetInstancesFile(siteId); // traversal-safe in siteId
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> raw = Yaml.read(in, Map.class);
            if (raw == null) {
                return Map.of();
            }
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            raw.forEach((instanceKey, value) -> {
                if (value instanceof Map<?, ?> configMap) {
                    out.put(instanceKey, frozen(configMap));
                }
            });
            return out;
        } catch (IOException | RuntimeException e) {
            // A hand-corrupted file must not 500 every page render for the tenant — degrade to "no config" and
            // warn, as SitePluginConfigService does. Empty config = widget defaults, so pages keep rendering.
            log.warn("could not read widget-instance config {} — treating as empty (widget defaults): {}",
                    file, e.toString());
            return Map.of();
        }
    }

    /**
     * One instance's config as a widget may see it: {@code null} keys and values dropped at every
     * level, nested maps and lists unmodifiable. {@code WidgetView} copies only the top level, and its
     * {@code Map.copyOf} throws on a {@code null} value — so a hand-written {@code key: ~} in
     * {@code widget-instances.yml} used to break the page's render, and a nested list a widget received was the
     * cache's own mutable instance, shared with every other render of that site.
     */
    static Map<String, Object> frozen(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                out.put(String.valueOf(key), frozenValue(value));
            }
        });
        return java.util.Collections.unmodifiableMap(out);
    }

    private static Object frozenValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return frozen(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    out.add(frozenValue(item));
                }
            }
            return java.util.Collections.unmodifiableList(out);
        }
        return value;
    }

    private void writeFile(String siteId, Map<String, Map<String, Object>> file) {
        Path path = layout.widgetInstancesFile(siteId);
        boolean secretBearing = file.values().stream()
                .flatMap(m -> m.values().stream())
                .anyMatch(v -> cipher.isEncrypted(String.valueOf(v)));
        try {
            Files.createDirectories(path.getParent());
            if (file.isEmpty()) {
                Files.deleteIfExists(path); // no instances configured → no file
                return;
            }
            AtomicFiles.write(path, out -> Yaml.write(out, file));
            if (secretBearing) {
                restrictToOwner(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing widget-instance config " + path, e);
        }
    }

    private static void restrictToOwner(Path file) throws IOException {
        try {
            OwnerOnlyFiles.restrictToOwner(file);
        } catch (IOException cannotProtect) {
            Files.deleteIfExists(file);
            throw new IOException("refusing to persist a secret-bearing widget config without owner-only "
                    + "permissions: " + file, cannotProtect);
        }
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

    /** The composite storage key for one placement. Both halves are safe slugs, so {@code /} cannot collide. */
    private static String instanceKey(String type, String instance) {
        return type + "/" + instance;
    }

    private Object lockFor(String siteId) {
        return siteLocks[Math.floorMod(siteId.hashCode(), siteLocks.length)];
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[32];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
