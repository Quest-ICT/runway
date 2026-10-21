package mx.quest.runway.widget;

/**
 * A content-widget anchor found in a tenant template: an element bearing both an {@code id} (the
 * decoupled-logic selector target the kernel fills) and a {@code data-widget="<type>/<instance>"}
 * attribute (the self-declaring binding). The two live on the same element so they cannot drift.
 *
 * @param anchorId   the element's {@code id} — a safe slug, used to target the anchor with
 *                   {@code sel="#<anchorId>"} (Thymeleaf decoupled selectors match {@code #id},
 *                   not {@code [data-widget='…']} attribute values)
 * @param type       the widget-type key (the {@code <type>} half; matches a
 *                   {@link mx.quest.runway.spi.ContentWidget#key()})
 * @param instanceId the instance identifier (the {@code <instance>} half), letting one widget type
 *                   render differently per placement
 */
public record WidgetBinding(String anchorId, String type, String instanceId) {

    /** The original {@code data-widget} attribute value ({@code <type>/<instance>}). */
    public String dataWidget() {
        return type + "/" + instanceId;
    }
}
