package mx.quest.discover;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Emits a schema.org {@code Product} (with a nested {@code Offer}) from a product page's {@code content.schema}
 * entry — name/description/image/sku, {@code brand}→Organization, and {@code offers}(price/currency/availability/
 * url). Name and description fall back to the page's title/description. The node is {@code mainEntityOfPage}-linked
 * to this page's {@code WebPage}.
 */
final class ProductSchema implements SchemaType {

    @Override
    public String key() {
        return "Product";
    }

    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        Map<String, Object> cfg = sc.pageConfig();
        String name = Schemas.str(cfg, "name", ctx.page().title());
        if (name == null || name.isBlank()) {
            return List.of(); // nothing substantive to say — don't emit a bare {@type: Product} node
        }
        Map<String, Object> product = Ld.node("Product", null);
        Ld.put(product, "name", name);
        Ld.put(product, "description", Schemas.str(cfg, "description", ctx.page().description()));
        Ld.putList(product, "image", Schemas.absolute(Schemas.strings(cfg, "image"), sc.base()));
        Ld.put(product, "sku", Schemas.str(cfg, "sku"));
        Ld.ref(product, "mainEntityOfPage", sc.webpageId());
        if (sc.hasOrg()) {
            Ld.ref(product, "brand", sc.organizationId());
        }
        String price = Schemas.str(cfg, "price");
        if (price != null) {
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("@type", "Offer");
            offer.put("price", price);
            offer.put("priceCurrency", Schemas.str(cfg, "currency", "USD"));
            String availability = Schemas.str(cfg, "availability");
            if (availability != null) {
                offer.put("availability",
                        availability.startsWith("http") ? availability : "https://schema.org/" + availability);
            }
            offer.put("url", sc.canonical());
            product.put("offers", offer);
        }
        return List.of(product);
    }
}
