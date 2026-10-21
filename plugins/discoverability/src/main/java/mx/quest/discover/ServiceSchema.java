package mx.quest.discover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Emits a schema.org {@code Service} (or the entry's declared subtype, e.g. {@code ProfessionalService}) from a
 * service page's {@code content.schema} entry: name/description/{@code serviceType}/{@code areaServed}/{@code offers},
 * with {@code provider}→Organization and {@code mainEntityOfPage}→WebPage. Name and description fall back to the
 * page's title/description. An {@code offers:} list turns a pricing page's tiers into machine-readable
 * {@code Offer}s (a recurring tier declares {@code unit:} → a {@code UnitPriceSpecification}, e.g. per month).
 */
final class ServiceSchema implements SchemaType {

    @Override
    public String key() {
        return "Service";
    }

    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        Map<String, Object> cfg = sc.pageConfig();
        String name = Schemas.str(cfg, "name", ctx.page().title());
        if (name == null || name.isBlank()) {
            return List.of(); // nothing substantive to say — don't emit a bare {@type: Service} node
        }
        String type = Schemas.str(cfg, "type", "Service"); // Service | ProfessionalService
        Map<String, Object> service = Ld.node(type, null);
        Ld.put(service, "name", name);
        Ld.put(service, "description", Schemas.str(cfg, "description", ctx.page().description()));
        Ld.put(service, "serviceType", Schemas.str(cfg, "serviceType"));
        Ld.putList(service, "areaServed", Schemas.strings(cfg, "areaServed"));
        List<Map<String, Object>> offers = offers(cfg);
        if (!offers.isEmpty()) {
            service.put("offers", offers);
        }
        Ld.ref(service, "mainEntityOfPage", sc.webpageId());
        if (sc.hasOrg()) {
            Ld.ref(service, "provider", sc.organizationId());
        }
        return List.of(service);
    }

    /**
     * The {@code offers:} list mapped to schema.org {@code Offer} nodes. An offer needs a {@code price}; a
     * {@code unit:} (e.g. {@code month}) makes it a recurring {@code UnitPriceSpecification}, else a flat
     * {@code price}/{@code priceCurrency}. Currency defaults to USD. Offers without a price are skipped.
     */
    private static List<Map<String, Object>> offers(Map<String, Object> cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : Schemas.maps(cfg, "offers")) {
            String price = Schemas.str(o, "price");
            if (price == null) {
                continue;
            }
            String currency = Schemas.str(o, "currency", "USD");
            String unit = Schemas.str(o, "unit");
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("@type", "Offer");
            Ld.put(offer, "name", Schemas.str(o, "name"));
            if (unit != null) {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("@type", "UnitPriceSpecification");
                spec.put("price", price);
                spec.put("priceCurrency", currency);
                spec.put("unitText", unit);
                offer.put("priceSpecification", spec);
            } else {
                offer.put("price", price);
                offer.put("priceCurrency", currency);
            }
            out.add(offer);
        }
        return out;
    }
}
