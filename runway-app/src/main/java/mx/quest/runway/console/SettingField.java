package mx.quest.runway.console;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSettingsService;

/**
 * A presentation view of one plugin setting for the (app-level or per-tenant) settings form: the declared type
 * is normalized to one of five <b>input kinds</b> ({@code text}/{@code area}/{@code secret}/{@code bool}/
 * {@code select}) so the decoupled template branches on a single value instead of re-deriving the type in SpEL.
 * A secret's {@code value} is always blank (never echoed); {@code secretSet} says whether one is stored.
 *
 * <p>{@code label} and {@code help} are the declared {@code plugin.yml} keys — i18n <b>message keys</b> resolved
 * (in the template) against the plugin's own bundle; the label defaults to the setting {@code key} when the
 * plugin declares none, so the form always shows either a resolved human label or the key itself.
 *
 * <p>The facts line under a field is composed in the template (so it localizes) from {@code type},
 * {@code range} and {@code defaultValue}. {@code hint} is the older, English-only one-string summary that the
 * widget-instance form still shows.
 *
 * @param key           the setting key — the input's {@code name}
 * @param label         the label message key
 * @param inputKind     {@code text|area|secret|bool|select}
 * @param value         the current value (blank for a secret)
 * @param secretSet     whether a secret is stored
 * @param hint          the English one-line type/range/default summary (widget form)
 * @param type          the normalized declared type
 * @param range         the declared range, or {@code null}
 * @param defaultValue  the declared default as text, or {@code null} (always {@code null} for a secret)
 * @param help          the help-line message key, or {@code null}
 * @param helpLink      the resolved "Learn more" URL, or {@code null}
 * @param showWhen      the key of the boolean switch this field depends on, or {@code null}
 * @param hidden        whether the field starts hidden (its switch is off when the page renders)
 * @param options       the choices of a {@code select} field, in order; empty otherwise
 * @param platformOff   whether a platform-level switch this field needs is off (the form says so)
 * @param lockedBy      where the server itself sets this value, when it does ({@code null}: editable here)
 * @param lockState     for a locked field, what removing the server's line would do, as the suffix of its message key
 *                      {@code op.settings.locked.<state>} ({@code same}, {@code default}, {@code differs},
 *                      {@code none}); {@code null} when not locked or not known
 * @param adoptLabel    for a locked field the console can take over, the message key of the button that copies the
 *                      server's value into the console; {@code null}: no button
 */
public record SettingField(String key, String label, String inputKind, String value, boolean secretSet,
                           String hint, String type, String range, String defaultValue, String help,
                           String helpLink, String showWhen, boolean hidden, List<SettingOption> options,
                           boolean platformOff, String lockedBy, String lockState, String adoptLabel) {

    public SettingField {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** The form before {@code lockedBy}: an editable field. */
    public SettingField(String key, String label, String inputKind, String value, boolean secretSet, String hint,
                        String type, String range, String defaultValue, String help, String helpLink,
                        String showWhen, boolean hidden, List<SettingOption> options, boolean platformOff) {
        this(key, label, inputKind, value, secretSet, hint, type, range, defaultValue, help, helpLink, showWhen,
                hidden, options, platformOff, null, null, null);
    }

    /**
     * This field locked because the server itself sets it ({@code lockedBy} names where: the host's
     * {@code application.yml}, an environment variable, a command-line argument). The form shows it disabled with
     * that origin, and a save leaves it alone: one home per setting, and the host's wins.
     */
    public SettingField lockedBy(String origin) {
        return lockedBy(origin, null, null);
    }

    /**
     * {@link #lockedBy(String)}, also saying what removing the server's line would do ({@code state}, see
     * {@link #lockState()}) and, when the console can take the value over, offering the button that copies it
     * ({@code adoptLabel}, see {@link #adoptLabel()}).
     */
    public SettingField lockedBy(String origin, String state, String adoptLabel) {
        return new SettingField(key, label, inputKind, value, secretSet, hint, type, range, defaultValue, help,
                helpLink, showWhen, hidden, options, platformOff, origin, state, adoptLabel);
    }

    /**
     * Maps a rendered setting (value + secret-set, from either settings engine) to its plain form view: the help
     * line, but no select, no links, no dependency — the rest of the presentation keys are applied by
     * {@link SettingsForm}.
     */
    public static SettingField of(PluginSettingsService.RenderedSetting rendered) {
        PluginSettingDef def = rendered.def();
        String type = typeOf(def);
        String kind = switch (type) {
            case "secret" -> "secret";
            case "boolean" -> "bool";
            case "text", "list" -> "area";
            default -> "text";
        };
        String label = (def.label() != null && !def.label().isBlank()) ? def.label() : def.key();
        String defaultText = def.defaultValue() == null || "secret".equals(type) ? null : String.valueOf(def.defaultValue());
        String range = def.range() == null || def.range().isBlank() ? null : def.range();
        return new SettingField(def.key(), label, kind, rendered.value(), rendered.secretSet(), hint(def, type),
                type, range, defaultText, def.help() == null || def.help().isBlank() ? null : def.help(), null, null,
                false, List.of(), false);
    }

    /** This field with the presentation keys applied (see {@link SettingsForm}). */
    SettingField present(String inputKind, String help, String helpLink, String showWhen, boolean hidden,
                         List<SettingOption> options, boolean platformOff) {
        return new SettingField(key, label, inputKind, value, secretSet, hint, type, range, defaultValue, help,
                helpLink, showWhen, hidden, options, platformOff);
    }

    /** Whether the facts line shows the declared range (a select already lists its choices). */
    public boolean showsRange() {
        return range != null && !"select".equals(inputKind);
    }

    /** Whether the facts line shows the default (a checkbox's default reads as noise; a blank one says nothing). */
    public boolean showsDefault() {
        return defaultValue != null && !defaultValue.isBlank() && !"bool".equals(inputKind);
    }

    static String typeOf(PluginSettingDef def) {
        return def.type() == null ? "string" : def.type().toLowerCase(Locale.ROOT);
    }

    private static String hint(PluginSettingDef def, String type) {
        StringBuilder hint = new StringBuilder(type);
        if (def.range() != null && !def.range().isBlank()) {
            hint.append(" · ").append(def.range());
        }
        if (def.defaultValue() != null && !"secret".equals(type)) {
            hint.append(" · default: ").append(def.defaultValue());
        }
        return hint.toString();
    }
}
