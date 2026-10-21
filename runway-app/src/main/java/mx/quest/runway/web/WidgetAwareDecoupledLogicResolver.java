package mx.quest.runway.web;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import mx.quest.runway.widget.WidgetBinding;
import mx.quest.runway.widget.WidgetScanner;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateparser.markup.decoupled.IDecoupledTemplateLogicResolver;
import org.thymeleaf.templateparser.markup.decoupled.StandardDecoupledTemplateLogicResolver;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templateresource.StringTemplateResource;

/**
 * Resolves a template's decoupled logic as the author's {@code .th.xml} <b>plus</b> kernel-generated
 * rules that fill its content-widget anchors — so a designer declares a widget with a single
 * {@code data-widget} attribute and never writes any Thymeleaf.
 *
 * <p>For each {@link WidgetBinding} the {@link WidgetScanner} finds in the template, this resolver
 * appends a rule targeting the anchor by id:
 *
 * <pre>{@code <attr sel="#<id>" th:utext="${widgets['<id>']}"/>}</pre>
 *
 * At render time {@code th:utext} replaces the anchor's (preview-only) content with the markup the
 * controller pre-rendered into the {@code widgets} model map (keyed by anchor id), or empties it
 * when the entry is absent/blank (soft-degrade). The author's own logic is preserved verbatim: the
 * rules are spliced before the closing {@code </thlogic>} of the authored file (or of an empty
 * {@code <thlogic>} when the page has none). A template with no widget anchors is passed through to
 * the standard resolver unchanged.
 *
 * <p>Two deliberate choices: the fill targets {@code #id} because Thymeleaf decoupled selectors
 * match element ids, not {@code [data-widget='…']} attribute values (so a widget anchor carries both
 * an id and the {@code data-widget} binding on the same element, where they cannot drift); and the
 * markup comes from a plain model variable rather than a SpEL bean call, because a chrome widget is
 * rendered inside a pulled master fragment, whose expressions run in Thymeleaf's <b>restricted</b>
 * context where {@code @bean} access is forbidden — map-variable access is not. Stateless and
 * thread-safe; with the template cache on, it runs once per template name.
 */
public class WidgetAwareDecoupledLogicResolver implements IDecoupledTemplateLogicResolver {

    private static final String CLOSE = "</thlogic>";
    private static final String EMPTY_LOGIC = "<?xml version=\"1.0\"?>\n<thlogic>\n</thlogic>";

    /** Locates and reads the author's {@code .th.xml}, exactly as Thymeleaf would by default. */
    private final IDecoupledTemplateLogicResolver authoredResolver = new StandardDecoupledTemplateLogicResolver();

    @Override
    public ITemplateResource resolveDecoupledTemplateLogic(IEngineConfiguration configuration,
            String ownerTemplate, String resourceName, Set<String> selectors,
            ITemplateResource templateResource, TemplateMode templateMode) {

        ITemplateResource authored = authoredResolver.resolveDecoupledTemplateLogic(
                configuration, ownerTemplate, resourceName, selectors, templateResource, templateMode);

        List<WidgetBinding> bindings = WidgetScanner.scan(read(templateResource));
        if (bindings.isEmpty()) {
            return authored; // no widgets here — leave the author's logic (or its absence) untouched
        }

        String base = (authored != null && authored.exists()) ? read(authored) : EMPTY_LOGIC;
        return new StringTemplateResource(splice(base, generateRules(bindings)));
    }

    /** Generates one {@code th:utext} fill rule per anchor, keyed to the anchor's id. */
    private static String generateRules(List<WidgetBinding> bindings) {
        StringBuilder rules = new StringBuilder();
        for (WidgetBinding binding : bindings) {
            rules.append("<attr sel=\"#").append(binding.anchorId())
                    .append("\" th:utext=\"${widgets['").append(binding.anchorId()).append("']}\"/>\n");
        }
        return rules.toString();
    }

    /** Inserts {@code rules} just before the (last) closing {@code </thlogic>}; wraps if absent. */
    private static String splice(String base, String rules) {
        int idx = base.lastIndexOf(CLOSE);
        if (idx < 0) {
            return "<?xml version=\"1.0\"?>\n<thlogic>\n" + rules + CLOSE;
        }
        return base.substring(0, idx) + rules + base.substring(idx);
    }

    private static String read(ITemplateResource resource) {
        try (Reader reader = resource.reader()) {
            StringWriter writer = new StringWriter();
            reader.transferTo(writer);
            return writer.toString();
        } catch (IOException ex) {
            throw new UncheckedIOException("reading template resource " + resource.getDescription(), ex);
        }
    }
}
