package mx.quest.discover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Emits a schema.org {@code FAQPage} from a {@code faq:} list of question/answer pairs in the page's
 * {@code content.schema} entry. FAQ is the highest-leverage GEO type — answer engines lift Q&amp;A directly —
 * so it is the first built-in {@link SchemaType}. The node is {@code mainEntityOfPage}-linked to this page's
 * {@code WebPage} {@code @id}; each entry becomes a {@code Question} with an {@code acceptedAnswer}.
 */
final class FaqPageSchema implements SchemaType {

    @Override
    public String key() {
        return "FAQPage";
    }

    /** An FAQ page is what the page is: the questions become its own {@code mainEntity} (since 2.2.0). */
    @Override
    public boolean describesPage(String token) {
        return true;
    }

    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        return List.of(); // describes the page itself; see describePage
    }

    @Override
    public void describePage(Map<String, Object> page, HeadContext ctx, SchemaContext sc,
                             List<Map<String, Object>> graph) {
        List<Map<String, Object>> questions = questions(sc);
        if (!questions.isEmpty()) {
            page.put("mainEntity", questions);
        }
    }

    /** No pair with both {@code q} and {@code a}: the page is an FAQPage with no questions. */
    @Override
    public Gap gap(String token, Map<String, Object> block) {
        return questions(block).isEmpty() ? new Gap("discover.gap.faq",
                "{0} has no question with both q: and a:, so the page is an {0} with no questions.") : null;
    }

    private static List<Map<String, Object>> questions(SchemaContext sc) {
        return questions(sc.pageConfig());
    }

    private static List<Map<String, Object>> questions(Map<String, Object> block) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Map<String, Object> qa : Schemas.maps(block, "faq")) {
            String question = Schemas.str(qa, "q");
            String answer = Schemas.str(qa, "a");
            if (question == null || answer == null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("@type", "Question");
            node.put("name", question);
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("@type", "Answer");
            accepted.put("text", answer);
            node.put("acceptedAnswer", accepted);
            questions.add(node);
        }
        return questions;
    }
}
