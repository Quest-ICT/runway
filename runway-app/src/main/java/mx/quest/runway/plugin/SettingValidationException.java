package mx.quest.runway.plugin;

/**
 * A submitted plugin-setting value failed typed validation (not an int, out of range, not an allowed enum
 * value, …). Carries the offending setting {@link #key()} so the form can mark that field; the message is
 * operator-readable. Thrown by {@link PluginSettingsService} on save and turned into a field error by the
 * controller — never a 500.
 *
 * <p>The message is English. An exception made with a {@link #code()} also says which console message tells the
 * same thing in the operator's language, with its {@link #args()}; the English message stays its default, so a screen
 * without that message, or a log line, still reads well.
 */
public class SettingValidationException extends RuntimeException {

    private static final Object[] NO_ARGS = {};

    private final String key;
    private final String code;
    private final Object[] args;

    public SettingValidationException(String key, String message) {
        this(key, message, null);
    }

    /**
     * @param code the console message key that says this in the operator's language ({@code op.config.invalid.*}),
     *             or {@code null} for none
     * @param args that message's arguments
     */
    public SettingValidationException(String key, String message, String code, Object... args) {
        super(message);
        this.key = key;
        this.code = code;
        this.args = args == null ? NO_ARGS : args.clone();
    }

    /** The dotted setting key whose value was rejected. */
    public String key() {
        return key;
    }

    /** The console message key for this refusal, or {@code null} when it has none (the message is then English). */
    public String code() {
        return code;
    }

    /** The arguments of {@link #code()}'s message (never {@code null}). */
    public Object[] args() {
        return args.clone();
    }
}
