package mx.quest.runway.spi.model;

/**
 * The HTML a {@link mx.quest.runway.spi.ContentWidget} produces to fill its anchor on a page.
 * An {@linkplain #empty() empty} fragment is the <b>soft-degrade</b> result — when a widget
 * has nothing to render (missing instance, disabled feature) the page still renders, just
 * without the widget's content.
 *
 * @param html the markup to splice in at the widget's anchor ({@code null} normalized to empty)
 */
public record RenderFragment(String html) {

    private static final RenderFragment EMPTY = new RenderFragment("");

    public RenderFragment {
        html = (html == null) ? "" : html;
    }

    /** The empty fragment — the soft-degrade result (renders nothing). */
    public static RenderFragment empty() {
        return EMPTY;
    }

    /** A fragment of the given markup; {@code null} or empty yields {@link #empty()}. */
    public static RenderFragment of(String html) {
        return (html == null || html.isEmpty()) ? EMPTY : new RenderFragment(html);
    }

    /** Whether this fragment has no markup (the soft-degrade case). */
    public boolean isEmpty() {
        return html.isEmpty();
    }
}
