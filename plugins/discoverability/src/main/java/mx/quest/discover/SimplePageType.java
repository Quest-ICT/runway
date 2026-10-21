package mx.quest.discover;

import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * A schema.org page type that only names what the page is — {@code AboutPage}, {@code ContactPage},
 * {@code WebPage} and the like — with no properties of its own. Declared in {@code content.schema}, it becomes the
 * page's own {@code @type}, replacing the site's Default page type; so a site whose default is {@code Article} can
 * say its contact page is a {@code ContactPage}, and a single page can go back to a plain {@code WebPage}.
 */
final class SimplePageType implements SchemaType {

    /**
     * The page types this class serves, each registered as its own key. Not {@code ProfilePage} or {@code QAPage}:
     * Google requires a {@code mainEntity} on both (the person; the one user-submitted question with its answers),
     * which a type with no keys cannot give, so declaring them would only earn a Search Console error. Site-authored
     * questions are an {@code FAQPage}.
     */
    static final List<String> KEYS = List.of("WebPage", "AboutPage", "ContactPage", "ItemPage", "SearchResultsPage");

    private final String key;

    SimplePageType(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        return List.of(); // describes the page itself; see describesPage
    }

    @Override
    public boolean describesPage(String token) {
        return true;
    }
}
