package mx.quest.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Emits schema.org {@code Person} node(s) from a {@code content.schema} entry — a {@code people:} list yields
 * one {@code Person} per member (name/jobTitle/image/sameAs, {@code worksFor}→Organization); with no list, a
 * single {@code Person} is built from the entry's own fields. A person with no name is skipped.
 */
final class PersonSchema implements SchemaType {

    @Override
    public String key() {
        return "Person";
    }

    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        Map<String, Object> cfg = sc.pageConfig();
        List<Map<String, Object>> people = Schemas.maps(cfg, "people");
        List<Map<String, Object>> out = new ArrayList<>();
        if (people.isEmpty()) {
            Map<String, Object> single = person(cfg, sc);
            if (single != null) {
                out.add(single);
            }
        } else {
            for (Map<String, Object> member : people) {
                Map<String, Object> node = person(member, sc);
                if (node != null) {
                    out.add(node);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> person(Map<String, Object> p, SchemaContext sc) {
        String name = Schemas.str(p, "name");
        if (name == null) {
            return null;
        }
        Map<String, Object> node = Ld.node("Person", null);
        Ld.put(node, "name", name);
        Ld.put(node, "jobTitle", Schemas.str(p, "jobTitle"));
        Ld.putList(node, "image", Schemas.absolute(Schemas.strings(p, "image"), sc.base()));
        Ld.putList(node, "sameAs", Schemas.strings(p, "sameAs"));
        if (sc.hasOrg()) {
            Ld.ref(node, "worksFor", sc.organizationId());
        }
        return node;
    }
}
