package mx.quest.runway.console;

import java.util.List;

/**
 * One section of the settings form: a declared group, or the untitled section of ungrouped fields.
 *
 * @param label        the heading message key, or {@code null} for the untitled section
 * @param help         the one-line introduction message key, or {@code null}
 * @param helpLink     the resolved "Learn more" URL, or {@code null}
 * @param fields       the section's fields, in declaration order
 * @param advanced     the section's advanced fields, rendered in a collapsed disclosure after {@code fields}
 * @param advancedOpen whether that disclosure starts open — when one of its fields holds a non-default value, so
 *                     a setting that is in effect is never tucked out of sight
 * @param advancedShowWhen the switch the whole disclosure follows, when every advanced field shares it; else
 *                     {@code null}
 * @param advancedHidden whether the disclosure starts hidden (all its fields are, behind that switch)
 */
public record SettingSection(String label, String help, String helpLink, List<SettingField> fields,
                             List<SettingField> advanced, boolean advancedOpen, String advancedShowWhen,
                             boolean advancedHidden) {

    public SettingSection {
        fields = List.copyOf(fields);
        advanced = List.copyOf(advanced);
    }
}
