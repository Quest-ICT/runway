package mx.quest.discover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Emits one or more schema.org {@code LocalBusiness} nodes (or the entry's declared subtype, e.g.
 * {@code CafeOrCoffeeShop}) from a {@code content.schema} entry. A {@code locations:} list yields one node per
 * location (name/address/geo/telephone/openingHours/priceRange); with no list, a single node is built from the
 * entry's own fields. {@code geo} is emitted only when both latitude and longitude are provided.
 */
final class LocalBusinessSchema implements SchemaType {

    @Override
    public String key() {
        return "LocalBusiness";
    }

    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        Map<String, Object> cfg = sc.pageConfig();
        String type = Schemas.str(cfg, "type", "LocalBusiness"); // LocalBusiness | CafeOrCoffeeShop | …
        List<Map<String, Object>> locations = Schemas.maps(cfg, "locations");
        List<Map<String, Object>> out = new ArrayList<>();
        if (locations.isEmpty()) {
            out.add(business(type, cfg, ctx, sc, 1));
        } else {
            int index = 1;
            for (Map<String, Object> location : locations) {
                out.add(business(type, location, ctx, sc, index++));
            }
        }
        return out;
    }

    private static Map<String, Object> business(String type, Map<String, Object> b, HeadContext ctx,
            SchemaContext sc, int index) {
        // A stable @id per location (canonical#business-N) + a link back to the page, so each node is part of the
        // graph rather than a bare, unlinked node. geo is emitted below when the tenant supplies coordinates.
        Map<String, Object> node = Ld.node(type, sc.canonical() + "#business-" + index);
        Ld.ref(node, "mainEntityOfPage", sc.webpageId());
        Ld.put(node, "url", Schemas.absolute(Schemas.str(b, "url", sc.canonical()), sc.base()));
        Ld.put(node, "name", Schemas.str(b, "name", ctx.site().displayName()));
        Ld.put(node, "telephone", Schemas.str(b, "telephone"));
        Ld.putList(node, "image", Schemas.absolute(Schemas.strings(b, "image"), sc.base()));
        Ld.put(node, "priceRange", Schemas.str(b, "priceRange"));
        Ld.putList(node, "openingHours", Schemas.strings(b, "hours"));

        String street = Schemas.str(b, "street");
        String locality = Schemas.str(b, "locality");
        if (street != null || locality != null) {
            Map<String, Object> address = Ld.node("PostalAddress", null);
            Ld.put(address, "streetAddress", street);
            Ld.put(address, "addressLocality", locality);
            Ld.put(address, "addressRegion", Schemas.str(b, "region"));
            Ld.put(address, "postalCode", Schemas.str(b, "postalCode"));
            Ld.put(address, "addressCountry", Schemas.str(b, "country"));
            node.put("address", address);
        }

        String latitude = Schemas.str(b, "latitude");
        String longitude = Schemas.str(b, "longitude");
        if (latitude != null && longitude != null) {
            Map<String, Object> geo = new LinkedHashMap<>();
            geo.put("@type", "GeoCoordinates");
            geo.put("latitude", latitude);
            geo.put("longitude", longitude);
            node.put("geo", geo);
        }
        return node;
    }
}
