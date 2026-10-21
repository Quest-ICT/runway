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
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.plugin.PluginSettingsService.RenderedSetting;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.OwnerOnlyFiles;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * The <b>per-tenant</b> plugin config engine — the tenant-scoped sibling of {@link PluginSettingsService}. It
 * renders, validates and writes a plugin's declared {@code siteSettings:} schema over
 * {@code sites/<id>/plugins/<plugin>.yml} (inside the tenant folder, so it travels in the tenant backup — the
 * self-containment rule). Unlike app-level settings, changes apply <b>immediately</b>: on save it evicts the
 * tenant config cache and publishes a {@link SitePluginConfigChangedEvent} so the plugin can react.
 *
 * <p>Locked behaviours: values written <b>typed</b> so the plugin's config record binds naturally;
 * <b>unknown / hand-edited keys are preserved</b> (a schema upgrade must never wipe operator data);
 * <b>blank = remove the key = the plugin default</b>; a default-valued boolean is written only when it differs
 * from its default; a <b>secret</b> is stored {@code ENC(...)} and a blank secret on resubmit keeps the stored
 * one — unless a setting it is {@linkplain PluginSettingDef#secretBoundTo() bound to} changed in the same save,
 * which drops it; an absent file means all defaults. The flat config map is what the plugin binds; {@link #config} returns
 * it (cached).
 */
@Service
public class SitePluginConfigService {

    private static final Logger log = LoggerFactory.getLogger(SitePluginConfigService.class);

    private final StorageLayout layout;
    private final SecretCipher cipher;
    private final ApplicationEventPublisher events;
    private final StoreCache<String, Map<String, Object>> cache = new StoreCache<>(512);

    /**
     * Drops cached per-tenant plugin config when a site changes.
     *
     * <p><b>Wholesale replacement</b> (delete/restore) rewrites a tenant's plugin config files entirely, so the
     * whole cache goes — no stale per-tenant config may survive a restore. (Mirrors {@code TemplateCacheService}
     * / {@code PageStore}.)
     *
     * <p><b>Every other kind</b> evicts just <i>that site's</i> entries. This is the case that matters for an
     * operator editing {@code sites/<id>/plugins/<plugin>.yml} <b>out of band</b> and then calling the reload
     * endpoint: {@code SiteReloadService} publishes {@link SiteChangedEvent.Kind#UPDATED}, which is <b>not</b> a
     * wholesale replacement, so before this branch existed the reload evicted {@code SiteStore} and fanned out
     * to ~25 listeners while this cache quietly kept serving the pre-edit YAML until an unrelated restart.
     *
     * <p><b>Why that was worth fixing rather than documenting:</b> it failed in the <i>permissive</i> direction
     * with <i>false assurance</i>. The reload returned {@code {"reloaded":"<id>"}}, so an operator who had just
     * added a {@code robots.extra-disallow} to keep crawlers off a path reasonably concluded it was in force. It
     * was not, and nothing errored at any point. Found on a live deployment, and reproduced
     * against a live tenant with a full restart as the control.
     *
     * <p>Scoped rather than {@link StoreCache#invalidateAll()} on purpose: {@code UPDATED} also fires on every
     * ordinary console site save, and dropping all tenants' plugin config on one tenant's save would make a
     * many-tenant node refill constantly for no reason.
     *
     * <p>Note the console's own settings-save path ({@link #save}) evicts its exact key directly and does not
     * rely on this listener — which is precisely why the defect stayed invisible: the UI path always worked, and
     * only the file-edited-then-reloaded path was broken.
     */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            cache.invalidateAll();
            return;
        }
        String prefix = event.siteId() + PathSafety.KEY_SEPARATOR;
        cache.invalidateIf(key -> key.startsWith(prefix));
    }

    /**
     * Drops the cached config this event names. On the saving node {@link #save} already evicted it, so this is a
     * no-op there; it matters on a <b>peer</b>, where the cluster relay replays the event (review m14b) — before,
     * a peer kept serving the pre-save values for as long as its cache entry lived. Runs in the
     * {@link KernelEventOrder#STORES} band, so every consumer that re-reads the settings in the same dispatch sees
     * the new values.
     */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onConfigChanged(SitePluginConfigChangedEvent event) {
        if (event.siteId() != null && event.pluginId() != null
                && PathSafety.isSafeId(event.siteId()) && PathSafety.isSafeId(event.pluginId())) {
            cache.invalidate(cacheKey(event.siteId(), event.pluginId()));
        }
    }

    public SitePluginConfigService(StorageLayout layout, SecretCipher cipher, ApplicationEventPublisher events) {
        this.layout = layout;
        this.cipher = cipher;
        this.events = events;
    }

    /** A tenancy's flat config map for a plugin (cached, an empty map when no file). The plugin binds this. */
    public Map<String, Object> config(String siteId, String pluginId) {
        return cache.get(cacheKey(siteId, pluginId), key -> readFile(siteId, pluginId));
    }

    /**
     * The current value of every {@code siteSettings} key for the form. A secret is never echoed (blank value +
     * {@code secretSet}); a non-secret comes from the file, else the declared default.
     */
    public List<RenderedSetting> render(String siteId, String pluginId, List<PluginSettingDef> schema) {
        Map<String, Object> stored = config(siteId, pluginId);
        List<RenderedSetting> out = new ArrayList<>(schema.size());
        for (PluginSettingDef def : schema) {
            Object raw = stored.get(def.key());
            if (SettingType.of(def.type()) == SettingType.SECRET) {
                out.add(new RenderedSetting(def, "", raw != null && !String.valueOf(raw).isBlank()));
            } else {
                // Use asString (which JOINS a list with ", ") for the stored value too — not String.valueOf,
                // which renders a List as "[a, b, c]" with brackets. That bracketed form was then re-split on
                // the next save (coerceList keeps the '[' / ']' on the edge tokens) and re-rendered, so a list
                // field like geo.speakable / org.sameas accumulated another bracket pair on every save.
                String value = asString(raw != null ? raw : def.defaultValue());
                out.add(new RenderedSetting(def, value == null ? "" : value, false));
            }
        }
        return out;
    }

    /**
     * Validates and writes the submitted values, <b>preserving unknown keys</b>, then evicts the cache and
     * publishes the change event (immediate apply). Blank non-secret → key removed (the plugin default); a
     * boolean equal to its default → removed; a secret blank → kept; a bad value throws
     * {@link SettingValidationException} (a field error) and nothing is written.
     *
     * <p><b>Bound secrets.</b> A blank secret is kept only while every setting it declares in
     * {@link PluginSettingDef#secretBoundTo()} keeps its value; when one of them changes, the stored secret is
     * removed in the same write ({@link SecretBindings}), so a settings editor cannot redirect a stored password
     * to a host of their choosing.
     *
     * @return the keys of the stored secrets this save removed because a bound setting changed (empty when none) —
     *         the caller tells the user they must be entered again
     */
    public List<String> save(String siteId, String pluginId, List<PluginSettingDef> schema,
                             Map<String, String> submitted) {
        Map<String, Object> before = readFile(siteId, pluginId);
        Map<String, Object> merged = new LinkedHashMap<>(before); // unknown keys preserved
        for (PluginSettingDef def : schema) {
            applyOne(def, submitted == null ? null : submitted.get(def.key()), merged);
        }
        List<String> dropped = SecretBindings.toDrop(schema, submitted, before::get, merged::get);
        dropped.forEach(merged::remove);
        writeFile(siteId, pluginId, merged);
        cache.invalidate(cacheKey(siteId, pluginId));
        events.publishEvent(new SitePluginConfigChangedEvent(siteId, pluginId));
        if (!dropped.isEmpty()) {
            log.info("site '{}', plugin '{}': removed the stored secret(s) {} — a setting they are bound to changed "
                    + "and no new value was entered", siteId, pluginId, dropped);
        }
        log.info("saved per-tenant config for plugin '{}' in site '{}' ({} keys; applied immediately)",
                pluginId, siteId, merged.size());
        return dropped;
    }

    /** Drops a tenancy's cached config (e.g. on a site delete/restore). */
    public void evict(String siteId, String pluginId) {
        cache.invalidate(cacheKey(siteId, pluginId));
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
                into.remove(def.key()); // equals the default → omit (the plugin default applies)
            } else {
                into.put(def.key(), value); // a default-true boolean turned off is written explicitly
            }
            return;
        }
        if (raw == null || raw.isBlank()) {
            into.remove(def.key()); // blank = remove = the plugin default
            return;
        }
        into.put(def.key(), type.coerce(def, raw)); // throws SettingValidationException on a bad value
    }

    private static boolean isDefaultTrue(PluginSettingDef def) {
        Object dflt = def.defaultValue();
        return dflt != null && "true".equalsIgnoreCase(String.valueOf(dflt));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFile(String siteId, String pluginId) {
        Path file = layout.sitePluginConfigFile(siteId, pluginId); // traversal-safe
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> map = Yaml.read(in, Map.class);
            return map == null ? Map.of() : map;
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers a malformed YAML parse error (Jackson). A hand-corrupted config file must
            // not 500 every read/save of that plugin for the tenant — degrade to "no config" and warn, as
            // SeoConfigService does. blank config = plugin defaults, so the plugin keeps working.
            log.warn("could not read per-tenant plugin config {} — treating as empty (plugin defaults): {}",
                    file, e.toString());
            return Map.of();
        }
    }

    private void writeFile(String siteId, String pluginId, Map<String, Object> config) {
        Path file = layout.sitePluginConfigFile(siteId, pluginId);
        boolean secretBearing = config.values().stream().anyMatch(v -> cipher.isEncrypted(String.valueOf(v)));
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.write(file, out -> Yaml.write(out, config));
            if (secretBearing) {
                restrictToOwner(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing per-tenant plugin config " + file, e);
        }
    }

    /**
     * Restricts a secret-bearing config to its owner, <b>fail-secure</b>: if the filesystem can protect it via
     * neither POSIX permissions nor an ACL, the just-written file is deleted and the save fails loudly — a secret
     * is never left on disk with default (possibly world-readable) permissions.
     */
    private static void restrictToOwner(Path file) throws IOException {
        try {
            OwnerOnlyFiles.restrictToOwner(file);
        } catch (IOException cannotProtect) {
            Files.deleteIfExists(file);
            throw new IOException("refusing to persist a secret-bearing plugin config without owner-only "
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

    private static String cacheKey(String siteId, String pluginId) {
        return PathSafety.compoundKey(siteId, pluginId);
    }
}
