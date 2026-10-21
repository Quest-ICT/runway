package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSettingGroup;
import mx.quest.runway.plugin.PluginSettingsService.RenderedSetting;
import mx.quest.runway.plugin.SettingType;
import org.springframework.core.env.Environment;

/**
 * Lays a plugin's rendered settings out as the sections of the generic settings form, applying the descriptor's
 * <b>presentation keys</b> ({@link PluginSettingDef#group() group}, {@code help}, {@code advanced},
 * {@code showWhen}, {@code helpAnchor}, {@code inheritsFrom}, {@code requiresPlatform} and the descriptor's
 * {@code settingGroups}/{@code helpUrl}).
 *
 * <h2>Tolerant by design</h2>
 * Every key is presentation: none of them changes what is stored or how a value validates. So a mistake in
 * one — a group id nobody declared, a {@code showWhen} naming a missing or non-boolean key, an
 * {@code inheritsFrom} that cannot be resolved, a help URL that is not a plain path or http(s) URL — degrades
 * to the plain rendering of that field rather than failing the page or blocking the plugin. A descriptor with
 * none of the keys yields one untitled section holding every field in declaration order — the same layout as
 * before the keys existed (the form-wide changes, a select for a ranged enum and the localized facts line,
 * apply to it too).
 */
final class SettingsForm {

    /** A help-page fragment: the characters an HTML id commonly uses, nothing that could leave the attribute. */
    private static final Pattern SAFE_ANCHOR = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    /** How far an {@code inherit} chain is followed before giving up (a cycle or a pathological descriptor). */
    private static final int MAX_INHERIT_DEPTH = 8;
    private static final String INHERIT = "inherit";

    private SettingsForm() {
    }

    /**
     * Builds the form's sections.
     *
     * @param rendered   the schema's current values, in declaration order (from either settings engine)
     * @param descriptor the plugin's descriptor (groups and help URL)
     * @param appValue   the plugin's <b>running</b> app-level value for a key, or {@code null} if unknown — used
     *                   for {@code inheritsFrom: app:<key>} and {@code requiresPlatform}
     * @return the untitled section of ungrouped fields (when there are any) followed by each declared group
     *         that has at least one field, in declaration order
     */
    static List<SettingSection> build(List<RenderedSetting> rendered, PluginDescriptor descriptor,
                                      Function<String, String> appValue) {
        Map<String, RenderedSetting> byKey = new HashMap<>();
        for (RenderedSetting setting : rendered) {
            byKey.putIfAbsent(setting.def().key(), setting);
        }
        Map<String, PluginSettingGroup> groups = new LinkedHashMap<>();
        for (PluginSettingGroup group : descriptor.settingGroups()) {
            if (group != null && group.id() != null && !group.id().isBlank()) {
                groups.putIfAbsent(group.id(), group);
            }
        }

        Map<String, List<RenderedSetting>> members = new LinkedHashMap<>();
        members.put(null, new ArrayList<>());
        groups.keySet().forEach(id -> members.put(id, new ArrayList<>()));
        for (RenderedSetting setting : rendered) {
            String group = setting.def().group();
            members.get(group != null && groups.containsKey(group) ? group : null).add(setting);
        }

        List<SettingSection> sections = new ArrayList<>();
        members.forEach((groupId, settings) -> {
            if (settings.isEmpty()) {
                return;
            }
            List<SettingField> fields = new ArrayList<>();
            List<SettingField> advanced = new ArrayList<>();
            boolean advancedOpen = false;
            for (RenderedSetting setting : settings) {
                SettingField field = field(setting, byKey, descriptor.helpUrl(), appValue);
                if (setting.def().isAdvanced()) {
                    advanced.add(field);
                    advancedOpen |= differsFromDefault(setting);
                } else {
                    fields.add(field);
                }
            }
            // When every advanced field hangs off the same switch, the disclosure follows that switch too —
            // otherwise a closed "Advanced" would open onto nothing while the switch is off.
            String advancedShowWhen = commonShowWhen(advanced);
            boolean advancedHidden = advancedShowWhen != null && advanced.stream().allMatch(SettingField::hidden);
            PluginSettingGroup group = groupId == null ? null : groups.get(groupId);
            sections.add(group == null
                    ? new SettingSection(null, null, null, fields, advanced, advancedOpen, advancedShowWhen,
                            advancedHidden)
                    : new SettingSection(group.label() != null && !group.label().isBlank() ? group.label() : group.id(),
                            blankToNull(group.help()), helpLink(descriptor.helpUrl(), group.helpAnchor()),
                            fields, advanced, advancedOpen, advancedShowWhen, advancedHidden));
        });
        return sections;
    }

    /**
     * The plugin's running app-level value for a key: what the environment holds under
     * {@code runway.plugin.<id>.<key>} (that is what the plugin bound at startup), else the declared default.
     * A value saved since the last restart is deliberately <b>not</b> what this returns — it is not in effect yet.
     *
     * <p>Only a key the plugin <b>declares</b> in its app-level schema, and never a {@code secret} one: these
     * values are shown on <b>site</b> pages ("inherit (currently: …)"), so a descriptor pointing at a platform
     * secret, or at any other property under the prefix, must yield nothing rather than disclose it.
     */
    static Function<String, String> appValues(PluginDescriptor descriptor, Environment environment) {
        Map<String, PluginSettingDef> declared = new HashMap<>();
        for (PluginSettingDef def : descriptor.settings()) {
            if (def.key() != null) {
                declared.putIfAbsent(def.key(), def);
            }
        }
        String prefix = "runway.plugin." + descriptor.id() + ".";
        return key -> {
            PluginSettingDef def = declared.get(key);
            if (def == null || SettingType.of(def.type()) == SettingType.SECRET) {
                return null;
            }
            String running = environment.getProperty(prefix + key);
            if (running != null) {
                return running;
            }
            return def.defaultValue() == null ? null : String.valueOf(def.defaultValue());
        };
    }

    private static SettingField field(RenderedSetting setting, Map<String, RenderedSetting> byKey, String helpUrl,
                                      Function<String, String> appValue) {
        PluginSettingDef def = setting.def();
        SettingField plain = SettingField.of(setting);

        String inputKind = plain.inputKind();
        List<SettingOption> options = List.of();
        if ("enum".equals(plain.type()) && plain.range() != null) {
            String inherited = def.inheritsFrom() == null ? null : resolve(def.inheritsFrom(), byKey, appValue, 0);
            options = options(plain.range(), plain.value(), inherited);
            inputKind = "select";
        }

        // Hidden while ANY switch up the chain is off: a field under a hidden switch is as unreachable as the
        // switch. An unusable chain (a cycle, a dangling or non-boolean gate) leaves the plain, visible field.
        List<RenderedSetting> chain = gateChain(setting, byKey);
        String showWhen = chain.isEmpty() ? null : chain.get(0).def().key();
        boolean hidden = chain.stream().anyMatch(gate -> !"true".equalsIgnoreCase(gate.value()));

        boolean platformOff = false;
        if (def.requiresPlatform() != null && !def.requiresPlatform().isBlank()) {
            String platform = appValue.apply(def.requiresPlatform().trim());
            platformOff = platform != null && !truthy(platform);
        }

        return plain.present(inputKind, blankToNull(def.help()), helpLink(helpUrl, def.helpAnchor()), showWhen,
                hidden, options, platformOff);
    }

    /**
     * The switches above a field: its {@code showWhen} gate, that gate's gate, and so on. Empty when the field's
     * own {@code showWhen} is unusable — and also when the chain loops (A on B, B on A) or runs past
     * {@link #MAX_INHERIT_DEPTH}: a cycle would hide every field in it behind a checkbox that is itself hidden,
     * leaving nothing on the page that could ever reveal them.
     */
    private static List<RenderedSetting> gateChain(RenderedSetting setting, Map<String, RenderedSetting> byKey) {
        List<RenderedSetting> chain = new ArrayList<>();
        java.util.Set<RenderedSetting> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(setting);
        RenderedSetting gate = directGate(setting, byKey);
        while (gate != null) {
            if (!seen.add(gate) || chain.size() >= MAX_INHERIT_DEPTH) {
                return List.of();
            }
            chain.add(gate);
            gate = directGate(gate, byKey);
        }
        return chain;
    }

    /** A field's own gate when it is usable: present, not the field itself, and a boolean. */
    private static RenderedSetting directGate(RenderedSetting setting, Map<String, RenderedSetting> byKey) {
        String key = setting.def().showWhen();
        RenderedSetting gate = key == null ? null : byKey.get(key);
        return gate != null && gate != setting && "boolean".equals(SettingField.typeOf(gate.def())) ? gate : null;
    }

    /** The one {@code showWhen} every field shares, or {@code null} when they are none, or differ. */
    private static String commonShowWhen(List<SettingField> fields) {
        if (fields.isEmpty()) {
            return null;
        }
        String first = fields.get(0).showWhen();
        return first != null && fields.stream().allMatch(f -> first.equals(f.showWhen())) ? first : null;
    }

    /**
     * The enum's choices. A stored value outside the declared range is kept as the first, selected choice — the
     * form must never silently change a value on the next save just because the plugin narrowed its range. A
     * blank value (no default declared, nothing stored) gets a blank first choice for the same reason.
     */
    private static List<SettingOption> options(String range, String value, String inherited) {
        List<String> values = new ArrayList<>();
        for (String choice : SettingType.allowedValues(range)) {
            if (!values.contains(choice)) {
                values.add(choice);
            }
        }
        String current = value == null ? "" : value.trim();
        List<SettingOption> options = new ArrayList<>();
        if (!values.contains(current)) {
            options.add(new SettingOption(current, true, null));
        }
        for (String choice : values) {
            options.add(new SettingOption(choice, choice.equals(current), INHERIT.equals(choice) ? inherited : null));
        }
        return options;
    }

    /**
     * What an {@code inherit} choice resolves to: {@code app:<key>} is the plugin's running app-level value; any
     * other reference is a field of the same schema, followed again if that field is itself on {@code inherit}.
     * {@code null} when the chain breaks, loops, or ends blank — the form then shows a plain "inherit".
     */
    private static String resolve(String reference, Map<String, RenderedSetting> byKey,
                                  Function<String, String> appValue, int depth) {
        if (reference == null || reference.isBlank() || depth > MAX_INHERIT_DEPTH) {
            return null;
        }
        String ref = reference.trim();
        if (ref.startsWith("app:")) {
            String value = appValue.apply(ref.substring(4).trim());
            return INHERIT.equals(value) ? null : blankToNull(value);
        }
        RenderedSetting source = byKey.get(ref);
        if (source == null) {
            return null;
        }
        String value = source.value() == null ? "" : source.value().trim();
        if (INHERIT.equals(value)) {
            return resolve(source.def().inheritsFrom(), byKey, appValue, depth + 1);
        }
        return blankToNull(value);
    }

    /**
     * The "Learn more" URL, or {@code null}. The help URL must be a same-origin path or an http(s) URL, and the
     * anchor a plain id — anything else (a {@code javascript:} URL, a protocol-relative {@code //host}, quotes)
     * yields no link rather than a dangerous one.
     */
    static String helpLink(String helpUrl, String anchor) {
        if (helpUrl == null || anchor == null) {
            return null;
        }
        String url = helpUrl.trim();
        boolean path = url.startsWith("/") && !url.startsWith("//") && !url.startsWith("/\\");
        boolean web = url.startsWith("https://") || url.startsWith("http://");
        if (!(path || web) || url.contains("#") || url.chars().anyMatch(c -> c <= ' ' || c == '"' || c == '<')) {
            return null;
        }
        String fragment = anchor.trim().startsWith("#") ? anchor.trim().substring(1) : anchor.trim();
        return SAFE_ANCHOR.matcher(fragment).matches() ? url + "#" + fragment : null;
    }

    private static boolean differsFromDefault(RenderedSetting setting) {
        if (setting.secretSet()) {
            return true;
        }
        String value = setting.value() == null ? "" : setting.value().trim();
        Object declared = setting.def().defaultValue();
        String fallback = declared == null ? "" : String.valueOf(declared).trim();
        if (SettingType.of(setting.def().type()) == SettingType.LIST) {
            // A saved list renders joined with ", " while a declared default is often "a,b" (or a YAML list):
            // compare the items, not the punctuation, or an untouched list would read as changed.
            return !items(value).isEmpty() && !items(value).equals(items(fallback));
        }
        return !value.isEmpty() && !value.equals(fallback);
    }

    private static List<String> items(String list) {
        List<String> out = new ArrayList<>();
        for (String token : list.replace("[", "").replace("]", "").split("[,\\n]")) {
            if (!token.isBlank()) {
                out.add(token.trim());
            }
        }
        return out;
    }

    private static boolean truthy(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
