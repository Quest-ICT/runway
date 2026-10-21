package mx.quest.runway.widget;

import java.util.List;
import java.util.Map;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.WidgetView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link ContentWidget} bean — core and plugin alike — and indexes them by their
 * stable {@link ContentWidget#key() type key}, the {@code <type>} half of a
 * {@code data-widget="<type>/<instance>"} anchor. This is the kernel half of the content-widget
 * seam: widgets arrive as ordinary beans (a plugin contributes one exactly as the core would).
 *
 * <p><b>Key collisions</b>: a duplicate or blank key among <b>kernel</b> widgets fails startup; a
 * <b>plugin</b> widget that collides (with the kernel or an earlier plugin in load order) or has a blank key is
 * refused with an ERROR naming both sides, and its plugin is marked degraded — a widget never silently shadows
 * another.
 *
 * <p><b>Soft-degrade is the rule.</b> {@link #render(String, WidgetView)} returns
 * {@link RenderFragment#empty()} for an <em>unknown</em> type (no such widget installed) just as a
 * present widget returns empty when it has nothing to show — so an anchor whose plugin is absent
 * renders empty, and the page still works. The host never throws because a widget is missing — nor because
 * one throws or returns {@code null} (contained: substituted with an empty fragment, logged and counted, and the page
 * is served uncacheable).
 *
 * <p>Thread-safe and immutable after construction.
 */
@Component
public class ContentWidgetRegistry {

    private final Map<String, ContentWidget> byKey;
    private final ContributionGuard guard;

    /**
     * Unit-test form: every widget counts as kernel code, so a collision fails fast.
     *
     * @param widgets every {@link ContentWidget} bean Spring discovered (possibly empty)
     * @throws IllegalStateException if a widget has a blank key or two share a key
     */
    public ContentWidgetRegistry(List<ContentWidget> widgets) {
        this(widgets, ContributionGuard.standalone());
    }

    /**
     * @param widgets every {@link ContentWidget} bean Spring discovered (possibly empty)
     * @param guard   the plugin-contribution containment policy
     * @throws IllegalStateException if a <b>kernel</b> widget has a blank key or two kernel widgets share one
     */
    @Autowired
    public ContentWidgetRegistry(List<ContentWidget> widgets, ContributionGuard guard) {
        this.guard = guard;
        this.byKey = Map.copyOf(guard.index("ContentWidget", widgets, ContentWidget::key));
    }

    /** Whether no widgets are registered (so every anchor soft-degrades to empty). */
    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    /**
     * Renders the widget of {@code type} for {@code view}, or {@link RenderFragment#empty()} when no
     * widget of that type is installed (soft-degrade). Never returns {@code null}.
     *
     * @param type the widget-type key from the anchor (never {@code null})
     * @param view the host-materialized widget view (never {@code null})
     * @return the fragment to splice at the anchor; empty to render nothing
     */
    public RenderFragment render(String type, WidgetView view) {
        ContentWidget widget = byKey.get(type);
        if (widget == null) {
            return RenderFragment.empty();
        }
        // Contained: a throwing widget — or one breaking the contract with a null fragment — renders empty, and the guard
        // marks the request degraded so the page carrying the hole is served uncacheable.
        return guard.callNonNull(widget, ContributionGuard.Axis.WIDGET, type, () -> widget.render(view),
                RenderFragment::empty);
    }
}
