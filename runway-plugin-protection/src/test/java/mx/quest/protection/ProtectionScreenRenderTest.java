package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.console.MenuRegistry;
import mx.quest.runway.console.OperatorProperties;
import mx.quest.runway.console.OperatorShellModel;
import mx.quest.runway.console.PluginTemplateResolver;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
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
 * The two protection render surfaces:
 * <ul>
 *   <li>The <b>app login-protection PANEL</b> ({@code templates/admin-app.html}) — a bare fragment bound to the
 *       plugin's own {@link AppProtectionPanel} bean via {@code ${@appProtectionPanel.*}}. This test RENDERS it with
 *       that bean in scope and is the regression guard for a real trap: {@code th:attr} evaluates in a RESTRICTED
 *       expression context that forbids {@code @bean} access, so every pre-filled field must use a <b>dedicated</b>
 *       processor ({@code th:value} / {@code th:placeholder} / {@code th:checked} / …), never {@code th:attr}. A
 *       relapse to {@code th:attr} would make this test (and the live page) throw.</li>
 *   <li>The <b>per-site screen</b> ({@code __plugin__/protection/site-protection}) — still a {@code contentTemplate}
 *       rendered inside {@code op-layout} with the admin topbar (the tenant realm keeps its dedicated screen).</li>
 * </ul>
 */
class ProtectionScreenRenderTest {

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
        SecretCipher cipher = Mockito.mock(SecretCipher.class);
        Mockito.when(cipher.isAvailable()).thenReturn(true);
        mx.quest.runway.mail.PlatformMailer mailer = Mockito.mock(mx.quest.runway.mail.PlatformMailer.class);
        Mockito.when(mailer.isConfigured()).thenReturn(true);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> buildProps =
                Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        return new OperatorShellModel(menu, siteResolver, new OperatorProperties(), cipher, mailer, buildProps);
    }

    // --- The app login-protection PANEL (fragment bound to the plugin's own bean) ---------------------------------

    /** An {@link AppProtectionPanel} over a real store seeded with {@code settings} (written to a temp data dir). */
    private static AppProtectionPanel panel(ProtectionSettings settings) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(Files.createTempDirectory("prot-panel"));
        ProtectionConfigStore store = new ProtectionConfigStore(
                new ProtectionProperties(), StorageLayout.from(props), new YamlStore(), passthroughCipher());
        store.saveApp(settings);
        return new AppProtectionPanel(store);
    }

    /** Renders the bare admin-app fragment with the {@code appProtectionPanel} bean in scope. */
    private String renderPanel(AppProtectionPanel panel) throws IOException {
        RunningPlugins running = runningWithProtection();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__app/plugins/protection/settings");
        request.setRequestURI("/__app/plugins/protection/settings");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        IContext ctx = webContext(request, new BindingAwareModelMap(), "appProtectionPanel", panel);
        return engine(running).process("__plugin__/protection/admin-app", ctx);
    }

    @Test
    void theAppPanelBindsItsFieldsFromThePluginBean() throws IOException {
        ProtectionSettings settings = new ProtectionSettings(true, 7, 300, 900,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "public-site-key", "", 0.5));
        String html = renderPanel(panel(settings));

        assertTrue(html.contains("action=\"/__app/protection\""), "the panel's immediate-apply form posts here");
        assertTrue(html.contains("Login protection"), "plugin i18n label resolved (protection.screen.heading)");
        assertTrue(html.contains("reCAPTCHA v2"), "a provider option label resolved from plugin i18n");
        assertTrue(html.contains("value=\"7\""), "the field pre-fills from the bean (maxAttempts=7 via th:value)");
        assertTrue(html.contains("public-site-key"), "the PUBLIC site key is rendered (it is not secret)");
        assertFalse(html.contains("protection.screen.heading"), "the raw plugin i18n key must not leak");
        // The panel is a fragment inside the kernel's plugin-settings page; its own markup passes the audit.
        List<String> problems = ConsoleA11yAudit.audit("admin-app panel", html, false);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theCaptchaSecretIsNeverRenderedIntoTheInput() throws IOException {
        ProtectionSettings withSecret = new ProtectionSettings(true, 5, 300, 900,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "public-site-key", "TOP-SECRET-VALUE", 0.5));
        String html = renderPanel(panel(withSecret));

        assertFalse(html.contains("TOP-SECRET-VALUE"), "the captcha secret is NEVER rendered into the page");
        assertTrue(html.contains("public-site-key"), "the PUBLIC site key is rendered (it is not secret)");
    }

    // --- The per-site screen (unchanged: a full contentTemplate rendered inside op-layout) ------------------------

    @Test
    void theSiteScreenRendersInsideOpLayoutWithTheAdminChrome() throws IOException {
        Site site = Mockito.mock(Site.class);
        Mockito.when(site.id()).thenReturn("acme");
        SiteResolver siteResolver = Mockito.mock(SiteResolver.class);
        Mockito.when(siteResolver.current()).thenReturn(Optional.of(site));

        RunningPlugins running = runningWithProtection();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__admin/protection");
        request.setRequestURI("/__admin/protection");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Model model = new BindingAwareModelMap();
        shellModel(running, siteResolver).populate(model, request, null);
        ProtectionForm.populate(model, ProtectionSettings.defaults(), true);
        model.addAttribute("contentTemplate", "__plugin__/protection/site-protection");
        model.addAttribute("pageTitleKey", "protection.screen.site.title");
        String html = engine(running).process("operator/op-layout", webContext(request, model, null, null));

        assertTrue(html.contains("rw-topbar-admin"), "plugin screen rendered inside op-layout with the admin topbar");
        assertFalse(html.contains("rw-topbar-app"));
        assertTrue(html.contains("action=\"/__admin/protection\""), "the per-site plugin form is present");
        assertTrue(html.contains("Login protection"));
        // The whole page (kernel chrome + this screen) passes the console's accessibility audit.
        List<String> problems = ConsoleA11yAudit.audit("/__admin/protection", html);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // --- Thymeleaf harness ---------------------------------------------------------------------------------------

    /**
     * A web-aware Thymeleaf context carrying the model + a Spring evaluation context. When {@code beanName} is
     * non-null the bean is registered so {@code ${@beanName...}} resolves (the panel fragment reads its bean this
     * way). A {@link WebContext} (not a bare {@code Context}) is needed because the topbar builds
     * {@code @{/__ui-lang}} link expressions.
     */
    private static IContext webContext(MockHttpServletRequest request, Model model, String beanName, Object bean) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange, Locale.ENGLISH);
        model.asMap().forEach(ctx::setVariable);
        StaticApplicationContext appContext = new StaticApplicationContext();
        if (beanName != null) {
            appContext.getBeanFactory().registerSingleton(beanName, bean);
        }
        appContext.refresh();
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(appContext, DefaultConversionService.getSharedInstance()));
        return ctx;
    }

    /** A pass-through cipher: available (so a secret can be "stored"), identity encrypt/decrypt — enough for render. */
    private static SecretCipher passthroughCipher() {
        return new SecretCipher() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String encrypt(String plaintext) {
                return plaintext;
            }

            @Override
            public String decrypt(String value) {
                return value;
            }

            @Override
            public boolean isEncrypted(String value) {
                return false;
            }
        };
    }
}
