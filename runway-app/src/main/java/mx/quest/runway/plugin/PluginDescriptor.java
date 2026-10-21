package mx.quest.runway.plugin;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.storage.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The parsed {@code plugin.yml} — the single declarative descriptor the kernel reads <b>without loading plugin
 * code</b> (so it works for disabled / blocked / broken plugins). Bound by Jackson with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} off so a newer file read by an older kernel (and vice-versa) never blows up
 * — the descriptor evolves additively. Realm values are accepted case-insensitively
 * ({@code tenant} / {@code application}).
 *
 * <p>The raw fields mirror the YAML 1:1; the typed accessors ({@link #requirements()}, {@link #pluginVersion()})
 * apply the strict parse, and {@link #validate()} enforces the descriptor rules — both throw with a precise,
 * operator-readable reason that the discovery layer turns into a skip-and-log.
 *
 * @param id            stable id = install-dir name (path-safe; cannot traverse)
 * @param name          display name
 * @param version       plain {@code MAJOR.MINOR.PATCH}
 * @param mainClass      the {@code @Configuration} class, or {@code null} for a code-less library provider
 * @param enabled        {@code false} excludes the plugin (absent = enabled)
 * @param essential      honored only for the kernel security-floor allowlist (absent = false)
 * @param requires        hard dependency gates ({@code id} | {@code id >= v} | {@code id == v})
 * @param conflicts       rival ids the declarer steps aside for
 * @param permissions     declared grantable permissions (realm-tagged)
 * @param settings        app-level settings schema
 * @param siteSettings    per-tenant settings schema
 * @param widgetSettings  per-widget-type <b>per-instance</b> config schema (WIDGETS): keyed by the content
 *                        widget's {@code key()} (the {@code <type>} half of a {@code data-widget} anchor), each
 *                        value a settings schema the tenant console renders per placed instance
 * @param requiresSpi     the minimum kernel SPI level this plugin needs ({@code >=X.Y.Z}), or {@code null}
 * @param menu            admin-menu contributions
 * @param dashboardCards  dashboard-card contributions
 * @param settingGroups   the titled sections of the settings forms, in display order (presentation only)
 * @param helpUrl         the plugin's help page — a path ({@code /__plugin/<id>/static/...}) or absolute URL —
 *                        that a setting's or section's {@code helpAnchor} links into; {@code null} = no links
 * @param siteData        what the plugin declares about its per-site data ({@code siteData.personal}: the paths a
 *                        transfer copy leaves out); never {@code null} (absent = nothing declared)
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String mainClass,
        Boolean enabled,
        Boolean essential,
        List<String> requires,
        List<String> conflicts,
        List<PluginPermissionDecl> permissions,
        List<PluginSettingDef> settings,
        List<PluginSettingDef> siteSettings,
        Map<String, List<PluginSettingDef>> widgetSettings,
        List<PluginMenuDecl> menu,
        List<PluginCardDecl> dashboardCards,
        String requiresSpi,
        List<PluginSettingGroup> settingGroups,
        String helpUrl,
        PluginSiteData siteData) {

    private static final Logger log = LoggerFactory.getLogger(PluginDescriptor.class);

    private static final ObjectMapper MAPPER = YAMLMapper.builder()
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)       // forward/backward compatible
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)      // an omitted int/boolean defaults
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)              // realm: tenant / application
            .build();

    public PluginDescriptor {
        requires = copy(requires);
        conflicts = copy(conflicts);
        permissions = copy(permissions);
        settings = copy(settings);
        siteSettings = copy(siteSettings);
        widgetSettings = copyMapOfLists(widgetSettings);
        menu = copy(menu);
        dashboardCards = copy(dashboardCards);
        // Nulls dropped (an empty `- ` item in YAML): a presentation key must never stop a plugin loading.
        settingGroups = settingGroups == null ? List.of()
                : settingGroups.stream().filter(java.util.Objects::nonNull).toList();
        siteData = siteData == null ? PluginSiteData.none() : siteData;
    }

    /** The form before {@code siteData} (SPI 0.5.0). */
    public PluginDescriptor(String id, String name, String version, String mainClass, Boolean enabled,
                            Boolean essential, List<String> requires, List<String> conflicts,
                            List<PluginPermissionDecl> permissions, List<PluginSettingDef> settings,
                            List<PluginSettingDef> siteSettings, Map<String, List<PluginSettingDef>> widgetSettings,
                            List<PluginMenuDecl> menu, List<PluginCardDecl> dashboardCards, String requiresSpi,
                            List<PluginSettingGroup> settingGroups, String helpUrl) {
        this(id, name, version, mainClass, enabled, essential, requires, conflicts, permissions, settings,
                siteSettings, widgetSettings, menu, dashboardCards, requiresSpi, settingGroups, helpUrl, null);
    }

    /** The form before the settings-form presentation keys — every caller that predates them. */
    public PluginDescriptor(String id, String name, String version, String mainClass, Boolean enabled,
                            Boolean essential, List<String> requires, List<String> conflicts,
                            List<PluginPermissionDecl> permissions, List<PluginSettingDef> settings,
                            List<PluginSettingDef> siteSettings, Map<String, List<PluginSettingDef>> widgetSettings,
                            List<PluginMenuDecl> menu, List<PluginCardDecl> dashboardCards, String requiresSpi) {
        this(id, name, version, mainClass, enabled, essential, requires, conflicts, permissions, settings,
                siteSettings, widgetSettings, menu, dashboardCards, requiresSpi, null, null, null);
    }

    /** Parses a descriptor from a {@code plugin.yml} stream (does not close it). Throws on malformed YAML. */
    public static PluginDescriptor read(InputStream in) {
        return MAPPER.readValue(in, PluginDescriptor.class);
    }

    /** Whether the plugin ships no {@code mainClass} — a legal code-less library/asset provider. */
    public boolean isLibraryOnly() {
        return mainClass == null || mainClass.isBlank();
    }

    /** Whether the plugin is enabled (absent {@code enabled} = enabled). */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** Whether the descriptor self-marks essential (only honored for the kernel allowlist). */
    public boolean isEssential() {
        return essential != null && essential;
    }

    /** The parsed version (strict). */
    public PluginVersion pluginVersion() {
        return PluginVersion.parse(version);
    }

    /**
     * The minimum {@code RunwaySpi.VERSION} this plugin needs, or {@code null} when it declares no floor.
     *
     * <h2>Why a plugin needs to be able to say this</h2>
     * A plugin compiled against a newer SPI <b>crashes at runtime</b> on an older kernel — it loads, registers,
     * and then throws {@code NoSuchMethodError} the first time the affected path runs, which may be on a public
     * page request long after startup, with a stack trace naming a method the operator has never heard of.
     *
     * <p>That happened twice in one week before this existed (one plugin calling
     * {@code PageMeta.provenance()}, another using a newer {@code ContentRef} constructor), and the only
     * protection was a sentence in an upgrade note saying "kernel first, plugin second" — a rule enforced by an
     * operator reading a paragraph at the exact moment they are busy doing an upgrade.
     *
     * <p>Against the <b>SPI level</b>, not the kernel release: a plugin does not care which release the
     * kernel is, it cares that a method exists. Kernel releases also move for reasons that have
     * nothing to do with the plugin contract, so a version-number floor would go stale on its own.
     *
     * <p>Only a floor. The reverse direction is safe by the SPI evolution rule (old-arity constructors are
     * kept, nothing is removed), so a ceiling would let a plugin refuse a kernel that would have worked.
     *
     * @throws IllegalArgumentException if the declaration is present but not {@code >=X.Y.Z} — a floor that
     *                                  fails open is worse than none, because it reads as protection that is
     *                                  not there
     */
    public PluginVersion spiFloor() {
        if (requiresSpi == null || requiresSpi.isBlank()) {
            return null;
        }
        String text = requiresSpi.trim();
        if (!text.startsWith(">=")) {
            throw new IllegalArgumentException("malformed requiresSpi '" + requiresSpi
                    + "' (expected `>=X.Y.Z`; only a minimum is supported — see PluginDescriptor#spiFloor)");
        }
        return PluginVersion.parse(text.substring(2).trim());
    }

    /** The parsed requirements (strict — each entry must be {@code id} / {@code id >= v} / {@code id == v}). */
    public List<PluginRequirement> requirements() {
        List<PluginRequirement> parsed = new ArrayList<>(requires.size());
        for (String entry : requires) {
            parsed.add(PluginRequirement.parse(entry));
        }
        return List.copyOf(parsed);
    }

    /**
     * Validates the descriptor's structural rules, throwing the first violation with a precise reason.
     *
     * @throws IllegalArgumentException on a blank/unsafe id, an unparsable version or requirement, a
     *                                  permission / menu / card entry missing its required {@code realm}, or a
     *                                  malformed {@code siteData.personal} path
     */
    public void validate() {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("plugin descriptor is missing an id");
        }
        if (!PathSafety.isSafeId(id)) {
            throw new IllegalArgumentException("plugin id '" + id + "' is not a safe directory name");
        }
        pluginVersion();   // throws on a bad version
        requirements();    // throws on a bad requires entry
        spiFloor();        // throws on a bad requiresSpi — MALFORMED, never silently ignored
        for (PluginPermissionDecl permission : permissions) {
            if (permission.id() == null || permission.id().isBlank()) {
                throw new IllegalArgumentException("plugin '" + id + "' declares a permission with no id");
            }
            if (!PathSafety.isSafeId(permission.id())) {
                // a safe id cannot contain the key separator, so the namespaced <id>|<perm> key stays injective
                throw new IllegalArgumentException(
                        "plugin '" + id + "' permission id '" + permission.id() + "' is not a safe identifier");
            }
            if (permission.realm() == null) {
                throw new IllegalArgumentException(
                        "plugin '" + id + "' permission '" + permission.id() + "' is missing a realm");
            }
        }
        for (PluginMenuDecl entry : menu) {
            if (entry.realm() == null) {
                throw new IllegalArgumentException("plugin '" + id + "' has a menu entry missing a realm");
            }
        }
        for (PluginCardDecl card : dashboardCards) {
            if (card.realm() == null) {
                throw new IllegalArgumentException("plugin '" + id + "' has a dashboard card missing a realm");
            }
        }
        for (String personal : siteData.personal()) {
            String problem = PluginSiteData.problemWith(personal);
            if (problem != null) {
                // Refused rather than ignored: a personal-data declaration that cannot be honoured would let the data
                // travel in a transfer copy. A refused plugin is not loaded, so its whole folder is left out instead.
                throw new IllegalArgumentException("plugin '" + id + "' siteData.personal entry '" + personal + "' "
                        + problem + " (paths are relative to sites/<id>/plugins/" + id + "/; end a folder with /)");
            }
        }
        widgetSettings.forEach((widgetType, defs) -> {
            if (widgetType == null || widgetType.isBlank()) {
                throw new IllegalArgumentException("plugin '" + id + "' has a widgetSettings entry with a blank widget type");
            }
            for (PluginSettingDef def : defs) {
                if (def.key() == null || def.key().isBlank()) {
                    throw new IllegalArgumentException("plugin '" + id + "' widget '" + widgetType
                            + "' declares a setting with no key");
                }
            }
        });
        warnOnUnknownSecretBindings("settings", settings);
        warnOnUnknownSecretBindings("siteSettings", siteSettings);
        widgetSettings.forEach((widgetType, defs) -> warnOnUnknownSecretBindings("widgetSettings." + widgetType, defs));
    }

    /**
     * Logs a {@code WARN} for each {@code secretBoundTo} entry of a secret in {@code schema} that names no
     * <em>other</em> setting of that same schema (an unknown key, a key of another schema, or the secret itself).
     * Such a binding can never match ({@code SecretBindings} ignores it), so the secret is kept on a blank resubmit
     * whatever happens to the setting the author meant — almost always a typo that silently defeats the protection.
     * Not a refusal: descriptors are read leniently (a presentation or safety key never stops a plugin loading).
     */
    private void warnOnUnknownSecretBindings(String schemaName, List<PluginSettingDef> schema) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (PluginSettingDef def : schema) {
            if (def.key() != null) {
                keys.add(def.key());
            }
        }
        for (PluginSettingDef secret : schema) {
            for (String bound : secret.secretBindings()) {
                if (!keys.contains(bound) || bound.equals(secret.key())) {
                    log.warn("plugin '{}' {} setting '{}': secretBoundTo names '{}', which is not another setting of "
                                    + "{} — the binding is ignored, so the stored secret is kept on a blank resubmit "
                                    + "whatever that key does (check the spelling)",
                            id, schemaName, secret.key(), bound, schemaName);
                }
            }
        }
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    private static Map<String, List<PluginSettingDef>> copyMapOfLists(Map<String, List<PluginSettingDef>> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, List<PluginSettingDef>> out = new LinkedHashMap<>();
        map.forEach((key, defs) -> out.put(key, copy(defs)));
        return Map.copyOf(out);
    }
}
