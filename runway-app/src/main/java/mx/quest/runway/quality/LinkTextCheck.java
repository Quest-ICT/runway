package mx.quest.runway.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Link text that says nothing about where the link goes.
 *
 * <ul>
 *   <li>{@code link.empty} — WARNING: a link with no accessible name at all (no text, no {@code aria-label}, no
 *       image {@code alt}, no {@code title}) — typically an icon-only link. A screen reader announces just
 *       "link". A warning rather than an error because such links usually live in a master (social icons) and
 *       would otherwise block every page of the site for a fault the page author did not make.</li>
 *   <li>{@code link.generic} — INFO: link text that only makes sense next to its surroundings ("click here",
 *       "read more", "aquí", "leer más", "ici"…). Lists of links read out of context — by assistive technology or
 *       a search engine — lose the destination.</li>
 * </ul>
 *
 * <p>Only {@code <a href>} elements are checked (an anchor without {@code href} is not a link). Each kind is
 * reported once with a count.
 */
@Component
public class LinkTextCheck implements PageQualityCheck {

    /** Generic link texts, normalized (lower-case, trimmed, edge punctuation dropped). English, Spanish, French. */
    static final Set<String> GENERIC = Set.of(
            "click here", "click", "here", "read more", "more", "learn more", "more info", "this", "link",
            "this link", "go", "continue",
            "aquí", "aqui", "haz clic aquí", "haga clic aquí", "clic aquí", "click aquí", "pulsa aquí", "pincha aquí",
            "leer más", "más", "mas", "ver más", "saber más", "más información", "este enlace", "enlace",
            "ici", "cliquez ici", "en savoir plus", "lire la suite", "plus", "ce lien", "lien");

    @Override
    public String key() {
        return "core.links";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        Document doc = RenderedHtml.parse(page);
        int empty = 0;
        int generic = 0;
        String firstGeneric = null;
        for (Element link : doc.select("a[href]")) {
            String name = RenderedHtml.accessibleName(link);
            if (name.isBlank()) {
                empty++;
            } else if (GENERIC.contains(RenderedHtml.normalize(name))) {
                generic++;
                if (firstGeneric == null) {
                    firstGeneric = name.strip();
                }
            }
        }
        List<Finding> findings = new ArrayList<>(2);
        if (empty > 0) {
            findings.add(Finding.warning("link.empty", empty + " link(s) have no text a screen reader can announce"));
        }
        if (generic > 0) {
            findings.add(Finding.info("link.generic",
                    generic + " link(s) use generic text such as \"" + firstGeneric + "\""));
        }
        return findings;
    }
}
