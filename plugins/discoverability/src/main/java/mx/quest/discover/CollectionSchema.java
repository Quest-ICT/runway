package mx.quest.discover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Emits a schema.org {@code ItemList} for a collection / index page (a coffee listing, a services or blog index)
 * from an {@code items:} list of {@code {name, url}}. Without it a listing page is invisible <em>as a list</em> to
 * crawlers (no carousel eligibility) and answer engines. Registered under both {@code ItemList} and
 * {@code CollectionPage}; the node is {@code @id}'d and {@code mainEntityOfPage}-linked to the page. Declared as
 * {@code CollectionPage} it also makes the page itself a {@code CollectionPage} whose {@code mainEntity} is the list
 * (since 2.2.0; until then the page stayed the site default and only the list was emitted).
 */
final class CollectionSchema implements SchemaType {

    @Override
    public String key() {
        return "ItemList";
    }

    /** {@code CollectionPage} names what the page is; {@code ItemList} only adds the list. */
    @Override
    public boolean describesPage(String token) {
        return "CollectionPage".equals(token);
    }

    @Override
    public void describePage(Map<String, Object> page, HeadContext ctx, SchemaContext sc,
                             List<Map<String, Object>> graph) {
        for (Map<String, Object> list : nodes(ctx, sc)) {
            graph.add(list);
            Ld.ref(page, "mainEntity", (String) list.get("@id"));
        }
    }

    /** No item with a {@code url}: no list is published (a CollectionPage is still a CollectionPage). */
    @Override
    public Gap gap(String token, Map<String, Object> block) {
        for (Map<String, Object> member : Schemas.maps(block, "items")) {
            if (Schemas.str(member, "url") != null) {
                return null;
            }
        }
        return new Gap("discover.gap.items", "{0} has no item with a url:, so no list is published.");
    }

    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        List<Map<String, Object>> members = Schemas.maps(sc.pageConfig(), "items");
        List<Map<String, Object>> elements = new ArrayList<>();
        int position = 1;
        for (Map<String, Object> member : members) {
            String url = Schemas.str(member, "url");
            if (url == null) {
                continue; // a list item without a target URL is not useful
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("@type", "ListItem");
            item.put("position", position++);
            Ld.put(item, "name", Schemas.str(member, "name"));
            item.put("url", url.startsWith("/") ? sc.base() + url : url);
            elements.add(item);
        }
        if (elements.isEmpty()) {
            return List.of();
        }
        Map<String, Object> list = Ld.node("ItemList", sc.canonical() + "#itemlist");
        Ld.ref(list, "mainEntityOfPage", sc.webpageId());
        list.put("itemListElement", elements);
        return List.of(list);
    }
}
