package mx.quest.runway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * One declarative setting in a plugin's {@code settings:} (app-level) or {@code siteSettings:} (per-tenant)
 * schema. The kernel renders, validates and persists the form generically; the plugin never sees
 * it. This record <b>parses and models</b> the schema (and reserves its storage paths); the typed
 * write/validate/evict machinery and secret {@code ENC(...)} encryption live in the settings services.
 *
 * <p>The keys after {@code range} are <b>presentation only</b> and all optional: they change how the form is
 * laid out and explained, never what is stored or how a value validates. A descriptor that uses none of them
 * renders as a flat list in declaration order, exactly as before they existed, and an older kernel ignores
 * them (the descriptor is read with unknown keys ignored), so a plugin can adopt them without a kernel floor.
 *
 * @param key             the dotted setting key (the plugin's config-properties prefix tail)
 * @param type            the entry type ({@code string|secret|text|int|decimal|boolean|enum|list})
 * @param defaultValue    the default (YAML key {@code default}); {@code null} = none
 * @param label           an i18n label key
 * @param range           an optional constraint hint (e.g. a numeric range or enum values), free-form for now
 * @param help            an i18n key for one short line shown <b>under</b> the field, always visible — not a
 *                        hover tooltip, which touch screens cannot open and screen readers often skip
 * @param group           the id of a {@link PluginSettingGroup} this field renders under; unknown or absent =
 *                        the ungrouped section at the top
 * @param advanced        {@code true} puts the field in its section's collapsed "Advanced" disclosure
 * @param showWhen        the key of a <b>boolean</b> setting in the same schema; the field is hidden while that
 *                        switch is off (still submitted, so its stored value survives a save)
 * @param helpAnchor      a fragment of the plugin's {@link PluginDescriptor#helpUrl() help page}; renders a
 *                        "Learn more" link for text too long for the help line
 * @param inheritsFrom    for an enum with an {@code inherit} option: where that option takes its value from —
 *                        a key in the same schema, or {@code app:<key>} for one of the plugin's app-level
 *                        settings — so the form can say what "inherit" currently means
 * @param requiresPlatform the key of one of the plugin's app-level <b>boolean</b> settings that must be on for
 *                        this field to have any effect; while it is off the form says so at the field
 * @param secretBoundTo   for a {@code secret}: the keys of settings in the same schema the secret is only valid
 *                        together with — typically where it is sent ({@code [smtpHost, smtpPort, smtpUsername]}).
 *                        When a save changes any of them and the secret field is left blank, the stored secret is
 *                        <b>dropped</b> instead of kept, and the form says it must be entered again. Without it,
 *                        whoever may edit the settings could repoint the host at their own server and have the
 *                        plugin hand them the stored password. Never {@code null} (absent = empty = the old
 *                        "blank keeps the secret"); ignored on a non-secret; an unknown key never matches. Read
 *                        leniently ({@link SettingKeyListDeserializer}): a list, one key, or a comma-separated
 *                        string, and any malformed value reads as empty — it never stops the plugin loading.
 *                        Unlike the keys after {@code range}, this one changes what is <em>stored</em>.
 */
public record PluginSettingDef(
        String key,
        String type,
        @JsonProperty("default") Object defaultValue,
        String label,
        String range,
        String help,
        String group,
        Boolean advanced,
        String showWhen,
        String helpAnchor,
        String inheritsFrom,
        String requiresPlatform,
        @JsonDeserialize(using = SettingKeyListDeserializer.class) List<String> secretBoundTo) {

    public PluginSettingDef {
        secretBoundTo = secretBoundTo == null ? List.of()
                : secretBoundTo.stream().filter(Objects::nonNull).map(String::trim).filter(k -> !k.isEmpty())
                        .distinct().toList();
    }

    /** The form before {@code secretBoundTo} — every caller that predates it. */
    public PluginSettingDef(String key, String type, Object defaultValue, String label, String range, String help,
                            String group, Boolean advanced, String showWhen, String helpAnchor, String inheritsFrom,
                            String requiresPlatform) {
        this(key, type, defaultValue, label, range, help, group, advanced, showWhen, helpAnchor, inheritsFrom,
                requiresPlatform, null);
    }

    /** The original five-key form — every caller that predates the presentation keys. */
    public PluginSettingDef(String key, String type, Object defaultValue, String label, String range) {
        this(key, type, defaultValue, label, range, null, null, null, null, null, null, null, null);
    }

    /**
     * The settings this secret is bound to ({@link #secretBoundTo()}), or empty when this is not a {@code secret}
     * — a binding on any other type means nothing and is ignored.
     */
    public List<String> secretBindings() {
        return SettingType.of(type) == SettingType.SECRET ? secretBoundTo : List.of();
    }

    /** Whether the field belongs in its section's collapsed "Advanced" disclosure. */
    public boolean isAdvanced() {
        return advanced != null && advanced;
    }
}
