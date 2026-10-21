package mx.quest.runway.plugin;

/**
 * A titled section of a plugin's settings form (descriptor key {@code settingGroups:}). Fields join a section
 * through their own {@link PluginSettingDef#group() group} key; sections render in the order they are declared,
 * after any ungrouped fields, and a section with no fields in the schema being rendered is not shown — so one
 * list serves the app-level and the per-tenant schema alike. Presentation only.
 *
 * @param id         the id fields refer to
 * @param label      an i18n key for the section heading (falls back to the id)
 * @param help       an optional i18n key for a one-line introduction under the heading
 * @param helpAnchor an optional fragment of the plugin's help page, rendered as a "Learn more" link
 */
public record PluginSettingGroup(String id, String label, String help, String helpAnchor) {
}
