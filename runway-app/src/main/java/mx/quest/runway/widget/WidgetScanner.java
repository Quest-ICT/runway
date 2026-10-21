package mx.quest.runway.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds content-widget anchors in a tenant template's HTML. An anchor is a single start tag that
 * carries both an {@code id} and a {@code data-widget="<type>/<instance>"} attribute, e.g.
 * {@code <div id="home-slider" data-widget="carousel/home-slider"></div>}.
 *
 * <p>This is attribute <em>extraction</em>, not structural HTML rewriting — the kernel never edits
 * the rendered markup itself; it only reads which anchors exist so it can generate decoupled-logic
 * rules that target them by {@code #id}. Both halves of the binding and the anchor id must be
 * <b>safe slugs</b> ({@code [A-Za-z0-9_-]+}); an anchor that is malformed (no id, or a
 * non-conforming id / {@code data-widget} value) is <b>skipped</b> rather than guessed at — its
 * author placeholder simply stays put, which surfaces the mistake.
 *
 * <p>Pure and stateless: {@link #scan(String)} is a function of its input.
 */
public final class WidgetScanner {

    private static final String SLUG = "[A-Za-z0-9_-]+";

    /** A start tag (no nested {@code <}/{@code >}) carrying a {@code data-widget} attribute. */
    private static final Pattern WIDGET_TAG =
            Pattern.compile("<[a-zA-Z][^<>]*\\bdata-widget\\s*=\\s*\"([^\"]*)\"[^<>]*>");

    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*\"([^\"]*)\"");

    private static final Pattern DATA_WIDGET_VALUE = Pattern.compile("(" + SLUG + ")/(" + SLUG + ")");

    private static final Pattern SAFE_ID = Pattern.compile(SLUG);

    private WidgetScanner() {
    }

    /**
     * Extracts the well-formed widget anchors from {@code html}, in document order.
     *
     * @param html the rendered/authored template markup ({@code null} treated as empty)
     * @return the bindings found (never {@code null}; empty when there are none)
     */
    public static List<WidgetBinding> scan(String html) {
        List<WidgetBinding> bindings = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return bindings;
        }
        Matcher tag = WIDGET_TAG.matcher(html);
        while (tag.find()) {
            String value = tag.group(1).trim();
            String tagText = tag.group();

            Matcher idMatcher = ID_ATTR.matcher(tagText);
            if (!idMatcher.find()) {
                continue; // a data-widget anchor with no id cannot be targeted — skip
            }
            String anchorId = idMatcher.group(1).trim();
            if (!SAFE_ID.matcher(anchorId).matches()) {
                continue;
            }
            Matcher valueMatcher = DATA_WIDGET_VALUE.matcher(value);
            if (!valueMatcher.matches()) {
                continue; // not exactly <safe-type>/<safe-instance>
            }
            bindings.add(new WidgetBinding(anchorId, valueMatcher.group(1), valueMatcher.group(2)));
        }
        return bindings;
    }
}
