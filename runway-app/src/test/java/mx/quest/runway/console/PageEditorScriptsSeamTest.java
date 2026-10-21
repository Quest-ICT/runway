package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;

/**
 * The self-hosted CodeMirror 6 bundle serves at {@code /__assets/vendor/codemirror.min.js} and the
 * optional {@code #op-scripts} seam in op-layout pulls a screen's {@code #scripts} fragment when the model
 * carries {@code contentScripts}, collapsing cleanly (no dead node, no null-template error) when it does not.
 */
@SpringBootTest
class PageEditorScriptsSeamTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-e0");
        registry.add("runway.storage.external", () -> external.toString());
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SpringTemplateEngine templateEngine;
    @Autowired
    private ApplicationContext applicationContext;

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theCodeMirrorBundleIsServedSelfHostedFromTheClasspath() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        // The IIFE exposes window.RunwayEditor — the facade the editor screen wires against.
        mvc.perform(get("/__assets/vendor/codemirror.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RunwayEditor")));
        mvc.perform(get("/__assets/vendor/codemirror.min.css")).andExpect(status().isOk());
    }

    @Test
    void theScriptsSeamPullsTheFragmentWhenContentScriptsIsSet() {
        String html = renderLayout("operator/testkit-shell");
        assertTrue(html.contains("TESTKIT_SCRIPTS_MARKER"),
                "the #scripts fragment is pulled into #op-scripts when contentScripts is set");
        assertTrue(html.contains("/__assets/vendor/codemirror.min.js"),
                "the pulled fragment carries the editor bundle tag");
    }

    @Test
    void theScriptsSeamCollapsesWhenContentScriptsIsAbsent() {
        String html = renderLayout(null);
        assertFalse(html.contains("TESTKIT_SCRIPTS_MARKER"), "no scripts fragment is pulled");
        assertFalse(html.contains("id=\"op-scripts\""),
                "the #op-scripts placeholder is removed (~{}), leaving no dead node");
    }

    /** Renders op-layout in isolation with the testkit fragments as topbar/content; contentScripts optional. */
    private String renderLayout(String contentScripts) {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("GET", "/__app")));
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("appName", "Runway");
        ctx.setVariable("assetVersion", "test");
        ctx.setVariable("pageTitleKey", "op.test.title");
        ctx.setVariable("topbarFragment", "operator/testkit-shell");
        ctx.setVariable("contentTemplate", "operator/testkit-shell");
        if (contentScripts != null) {
            ctx.setVariable("contentScripts", contentScripts);
        }
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, DefaultConversionService.getSharedInstance()));
        return templateEngine.process("operator/op-layout", ctx);
    }
}
