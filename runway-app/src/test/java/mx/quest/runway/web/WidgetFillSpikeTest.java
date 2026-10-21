package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateparser.markup.decoupled.IDecoupledTemplateLogicResolver;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templateresource.StringTemplateResource;

/**
 * De-risking spike for the widget fill's load-bearing mechanic: a custom {@link IDecoupledTemplateLogicResolver}
 * that injects a kernel-generated {@code th:utext} rule, targeting a {@code data-widget} anchor by
 * attribute-value selector, fills the anchor from a model variable — and renders empty (soft-degrade)
 * when the variable is absent. Proves the parser-free, render-time widget-fill approach before building it.
 */
class WidgetFillSpikeTest {

    /** Stand-in resolver: ignores authored logic, emits one generated rule for the {@code c/home} anchor. */
    private static final class WidgetSpikeResolver implements IDecoupledTemplateLogicResolver {
        @Override
        public ITemplateResource resolveDecoupledTemplateLogic(IEngineConfiguration configuration,
                String ownerTemplate, String resourceName, Set<String> selectors,
                ITemplateResource templateResource, TemplateMode templateMode) {
            String rule = "<attr sel=\"#slot\" th:utext=\"${widgets['c/home']}\"/>";
            return new StringTemplateResource("<?xml version=\"1.0\"?><thlogic>" + rule + "</thlogic>");
        }
    }

    private static SpringTemplateEngine engine(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);

        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("page.html"),
                "<!DOCTYPE html><html><head><title>t</title></head>"
                        + "<body><div id=\"slot\" data-widget=\"c/home\">PLACEHOLDER</div></body></html>",
                StandardCharsets.UTF_8);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));
        engine.setDecoupledTemplateLogicResolver(new WidgetSpikeResolver());
        return engine;
    }

    @Test
    void generatedUtextRuleFillsTheAnchorFromTheModel(@TempDir Path tmp) throws Exception {
        Context context = new Context();
        context.setVariable("widgets", Map.of("c/home", "<p class=\"w\">RENDERED</p>"));

        String html = engine(tmp).process(SiteTemplateResolver.renderSpec("acme", "__site__/acme/page"), context);

        assertTrue(html.contains("<p class=\"w\">RENDERED</p>"), () -> "widget markup must fill the anchor; got:\n" + html);
        assertFalse(html.contains("PLACEHOLDER"), () -> "the author's placeholder must be replaced; got:\n" + html);
    }

    @Test
    void absentModelEntrySoftDegradesToEmpty(@TempDir Path tmp) throws Exception {
        Context context = new Context();
        context.setVariable("widgets", Map.of()); // no entry for c/home

        String html = engine(tmp).process(SiteTemplateResolver.renderSpec("acme", "__site__/acme/page"), context);

        assertFalse(html.contains("PLACEHOLDER"), () -> "placeholder is replaced even with nothing to show; got:\n" + html);
        assertTrue(html.contains("id=\"slot\""), () -> "the anchor element survives, just empty; got:\n" + html);
    }
}
