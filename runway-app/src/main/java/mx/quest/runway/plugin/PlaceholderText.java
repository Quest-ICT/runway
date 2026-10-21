package mx.quest.runway.plugin;

/**
 * Keeps a stored setting's text literal when it is loaded into the Spring {@code Environment}, where a
 * <code>${...}</code> would otherwise be resolved: a value could pull in another property, such as the master key,
 * and send it out in a public header, a robots.txt or a page.
 *
 * <p>Only a <code>${</code> that Spring would resolve is escaped: one with its own closing <code>}</code>, found the way
 * Spring finds it (each <code>{</code> in between needs a <code>}</code> of its own first). Spring turns that escaped
 * <code>\${</code> back into <code>${</code>, so the setting reads exactly as written. A <code>${</code> without its
 * closing brace is never a placeholder: Spring leaves it alone, and it leaves an escape in front of it alone too, so
 * escaping it would hand the plugin <code>\${</code> and make the form show a pending restart after every start.
 *
 * <p>What this covers is <code>${</code> only. A {@code #{...}} is not a placeholder for the {@code Environment};
 * Spring evaluates it as an expression only inside {@code @Value} and {@code @Scheduled}, which the console never
 * feeds free text (their keys are numbers or a cron, checked on save).
 */
public final class PlaceholderText {

    private static final String PREFIX = "${";

    private PlaceholderText() {
    }

    /**
     * {@code text} escaped so that, read through the {@code Environment} or the binder, it is exactly {@code text}:
     * every <code>${</code> that Spring would resolve becomes <code>\${</code>; anything else is unchanged.
     */
    public static String literal(String text) {
        if (text == null || !text.contains(PREFIX)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        int from = 0;
        for (int at = text.indexOf(PREFIX); at >= 0; at = text.indexOf(PREFIX, at + PREFIX.length())) {
            if (closes(text, at)) {
                out.append(text, from, at).append('\\');
                from = at;
            }
        }
        return out.append(text, from, text.length()).toString();
    }

    /**
     * Whether the <code>${</code> at {@code start} has a closing brace as Spring's placeholder parser looks for it: a
     * <code>{</code> on the way opens a nested level that its own <code>}</code> closes first. With every such
     * <code>${</code> escaped, Spring never parses inside one, so this scan is the only one that matters.
     */
    private static boolean closes(String text, int start) {
        int nested = 0;
        for (int i = start + PREFIX.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '}') {
                if (nested == 0) {
                    return true;
                }
                nested--;
            } else if (c == '{') {
                nested++;
            }
        }
        return false;
    }
}
