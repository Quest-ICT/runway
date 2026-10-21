package mx.quest.runway.console;

/**
 * One choice of an enum setting rendered as a {@code <select>}.
 *
 * @param value     the stored value (also the visible text)
 * @param selected  whether it is the current value
 * @param inherited for the {@code inherit} choice of a field that declares {@code inheritsFrom}: the value it
 *                  resolves to right now, shown as "Inherit (currently: …)"; {@code null} otherwise or when the
 *                  source cannot be resolved
 */
public record SettingOption(String value, boolean selected, String inherited) {
}
