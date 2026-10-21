package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.console.MenuRegistry;
import mx.quest.runway.console.OperatorProperties;
import mx.quest.runway.console.OperatorShellModel;
import mx.quest.runway.console.PluginTemplateResolver;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.site.SiteResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.Model;
import org.springframework.validation.support.BindingAwareModelMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.context.IContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * The DoS screen renders inside the kernel {@code op-layout} shell with the app topbar (the reusable 1c seam),
 * with the plugin's own i18n resolving its {@code #{dos.*}} labels, the manual-block form present, and repeated
 * blocklist + offender rows populated. It never renders a secret (there is none on this screen).
 */
class DosScreenRenderTest {

    /** The plugin project root (where templates/ + i18n/ live) — the CWD when the module's tests run. */
    private static final Path PLUGIN_ROOT = Path.of(System.getProperty("user.dir"));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static PluginDescriptor descriptor() throws IOException {
        try (InputStream in = Files.newInputStream(PLUGIN_ROOT.resolve("plugin.yml"))) {
            return PluginDescriptor.read(in);
        }
    }

    private static SpringTemplateEngine engine(RunningPlugins running) {
        ClassLoaderTemplateResolver operator = new ClassLoaderTemplateResolver();
        operator.setPrefix("templates/");
        operator.setSuffix(".html");
        operator.setResolvablePatterns(Set.of("operator/*"));
        operator.setTemplateMode(TemplateMode.HTML);
        operator.setUseDecoupledLogic(true);
        operator.setCacheable(false);
        operator.setOrder(2);

        PluginTemplateResolver plugin = new PluginTemplateResolver(running);
        plugin.setCacheable(false);

        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasenames("classpath:i18n/operator-messages",
                "file:" + PLUGIN_ROOT.resolve("i18n").resolve("messages"));
        messages.setDefaultEncoding("UTF-8");
        messages.setUseCodeAsDefaultMessage(true);
        // As in production (OperatorMessageSource): no fallback to the HOST's locale. Without this the English
        // render resolved Spanish on a workstation whose LANG is es_MX — a test that passed or failed by machine.
        messages.setFallbackToSystemLocale(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(operator);
        engine.addTemplateResolver(plugin);
        engine.setTemplateEngineMessageSource(messages);
        return engine;
    }

    private static RunningPlugins runningWithProtection() throws IOException {
        LoadedPlugin loaded = new LoadedPlugin(descriptor(), PLUGIN_ROOT);
        RunningPlugins running = Mockito.mock(RunningPlugins.class);
        Mockito.when(running.installed()).thenReturn(List.of(loaded));
        return running;
    }

    private static OperatorShellModel shellModel(RunningPlugins running, SiteResolver siteResolver) {
        AuthorizationService authz = Mockito.mock(AuthorizationService.class);
        PermissionCatalog catalog = Mockito.mock(PermissionCatalog.class);
        MenuRegistry menu = new MenuRegistry(running, authz, catalog);
        mx.quest.runway.crypto.SecretCipher cipher = Mockito.mock(mx.quest.runway.crypto.SecretCipher.class);
        Mockito.when(cipher.isAvailable()).thenReturn(true);
        mx.quest.runway.mail.PlatformMailer mailer = Mockito.mock(mx.quest.runway.mail.PlatformMailer.class);
        Mockito.when(mailer.isConfigured()).thenReturn(true);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> buildProps =
                Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        return new OperatorShellModel(menu, siteResolver, new OperatorProperties(), cipher, mailer, buildProps);
    }

    private String render(List<DosController.OffenderRow> offenders, List<DosController.BlockRow> blocks)
            throws IOException {
        return render(offenders, blocks, List.of());
    }

    private String render(List<DosController.OffenderRow> offenders, List<DosController.BlockRow> blocks,
                          List<DosController.EndedRow> ended) throws IOException {
        RunningPlugins running = runningWithProtection();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__app/dos");
        request.setRequestURI("/__app/dos");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SiteResolver siteResolver = Mockito.mock(SiteResolver.class);
        Model model = new BindingAwareModelMap();
        shellModel(running, siteResolver).populate(model, request, null);
        model.addAttribute("offenders", offenders);
        model.addAttribute("blocks", blocks);
        model.addAttribute("ended", ended);
        model.addAttribute("contentTemplate", "__plugin__/protection/app-dos");
        model.addAttribute("pageTitleKey", "dos.screen.title");

        IContext ctx = webContext(request, model);
        return engine(running).process("operator/op-layout", ctx);
    }

    private static IContext webContext(MockHttpServletRequest request, Model model) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange, Locale.ENGLISH);
        model.asMap().forEach(ctx::setVariable);
        StaticApplicationContext appContext = new StaticApplicationContext();
        appContext.refresh();
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(appContext, DefaultConversionService.getSharedInstance()));
        return ctx;
    }

    @Test
    void theScreenRendersInsideOpLayoutWithTheAppChromeAndTheBlockForm() throws IOException {
        String html = render(List.of(), List.of());

        assertTrue(html.contains("rw-topbar-app"), "the DoS screen renders inside op-layout with the app topbar");
        assertFalse(html.contains("rw-topbar-admin"));
        assertTrue(html.contains("action=\"/__app/dos/block\""), "the manual-block form is present");
        assertTrue(html.contains("DoS activity"), "plugin i18n label resolved (dos.screen.heading)");
        assertFalse(html.contains("dos.screen.heading"), "the raw plugin i18n key must not leak");
        // Empty-state messages show when the lists are empty.
        assertTrue(html.contains("No addresses are currently blocked."));
    }

    /**
     * The DoS screen passes the console's structural accessibility audit with TWO rows in every table, so a
     * repeated row id (the template's selector anchor) or an unnamed control would show up here.
     */
    @Test
    void theScreenPassesTheConsoleAccessibilityAuditWithRepeatedRows() throws IOException {
        String html = render(
                List.of(new DosController.OffenderRow("198.51.100.9", 500, 42, true),
                        new DosController.OffenderRow("198.51.100.10", 300, 7, false)),
                List.of(new DosController.BlockRow("203.0.113.7", "abuse", "manual", "2026-07-08 12:00", true),
                        new DosController.BlockRow("203.0.113.8", "abuse", "auto", "2026-07-08 12:05", true)),
                List.of(new DosController.EndedRow("198.51.100.4", "auto", "auto", "2026-08-20 15:04"),
                        new DosController.EndedRow("198.51.100.5", "auto", "auto", "2026-08-20 15:09")));
        java.util.List<String> problems = ConsoleA11yAudit.audit("/__app/dos", html);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void aBlockedEntryAndAnOffenderRowRender() throws IOException {
        DosController.BlockRow block = new DosController.BlockRow(
                "203.0.113.7", "abuse", "manual", "2026-07-08 12:00", true);
        DosController.OffenderRow offender = new DosController.OffenderRow("198.51.100.9", 500, 42, true);

        String html = render(List.of(offender), List.of(block));

        assertTrue(html.contains("203.0.113.7"), "the blocked IP renders");
        assertTrue(html.contains("abuse"), "the block reason renders");
        assertTrue(html.contains("198.51.100.9"), "the offender IP renders");
        assertTrue(html.contains("42"), "the offender throttled count renders");
        // The per-row unblock form targets the unblock endpoint and carries the IP.
        assertTrue(html.contains("action=\"/__app/dos/unblock\""), "the per-row unblock form is present");
    }

    /**
     * A block that has ended renders in its own table.
     *
     * <p>An auto-block lasts fifteen minutes by default, so an operator asking "was the guard doing anything
     * when the site was slow at three?" previously arrived to an empty blocklist with no way to tell. This is
     * the screen half of that fix, and it is asserted through a real render because a decoupled-logic
     * selector that matches nothing fails <b>silently</b> — the row would come out structurally correct and
     * completely blank.
     */
    @Test
    void anEndedBlockRendersWithItsReasonAndTime() throws IOException {
        DosController.EndedRow ended = new DosController.EndedRow(
                "198.51.100.4", "auto: 60 breaches in 60s", "auto", "2026-08-20 15:04");

        String html = render(List.of(), List.of(), List.of(ended));

        assertTrue(html.contains("198.51.100.4"), "the address renders");
        assertTrue(html.contains("auto: 60 breaches in 60s"), "the reason is the part that answers the question");
        assertTrue(html.contains("2026-08-20 15:04"), "and when it ended");
        assertFalse(html.contains("No blocks have ended yet."), "the empty state must give way to the row");
        assertFalse(html.contains("dos.ended."), "no raw i18n key leaks: " + html.contains("dos.ended."));
    }

    /** With nothing to show, the empty state explains that the list is in memory and clears on restart. */
    @Test
    void theEndedBlocksEmptyStateExplainsItself() throws IOException {
        String html = render(List.of(), List.of());

        assertTrue(html.contains("No blocks have ended yet."));
        assertTrue(html.contains("empties on restart"),
                "an operator must not read an empty list as 'nothing was ever blocked'");
    }
}
