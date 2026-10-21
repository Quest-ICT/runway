package mx.quest.runway.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.console.OperatorLocaleResolver;
import mx.quest.runway.console.OperatorMessageSource;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginRuntime;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.LocaleResolver;

/**
 * {@code Messages}: public text by an explicitly named site (the site's bundle first, then plugin bundles,
 * add-only), independent of the thread's bound site and usable off-request; console text in the operator's UI
 * language, the fallback off a request; never a {@code null}, never an exception for a missing key.
 */
class MessagesImplTest {

    @TempDir
    Path external;
    private SiteResolver resolver;
    private MessagesImpl messages;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        resolver = new SiteResolver(new SiteStore(layout, new YamlStore()), TestPlatformSettings.empty());
        write(layout.siteDir("acme").resolve("i18n/messages.properties"), "contact.title=Write to Acme\n");
        write(layout.siteDir("acme").resolve("i18n/messages_es.properties"), "contact.title=Escribe a Acme\n");
        write(layout.siteDir("beta").resolve("i18n/messages.properties"), "contact.title=Write to Beta\n");
        Path plugin = external.resolve("plugins/contact");
        // One plugin bundle serves both namespaces (the console source appends it after the core bundles).
        write(plugin.resolve("i18n/messages.properties"),
                "contact.title=PLUGIN DEFAULT\ncontact.sent=Thanks, {0}!\ncontact.console.title=Contact form\n");
        write(plugin.resolve("i18n/messages_es.properties"), "contact.console.title=Formulario de contacto\n");
        PluginRuntime runtime = new PluginRuntime(List.of(new LoadedPlugin(PluginDescriptor.read(
                new ByteArrayInputStream("id: contact\nversion: 2.0.0\n".getBytes(StandardCharsets.UTF_8))), plugin)),
                getClass().getClassLoader(), Map.of(), false);
        ObjectProvider<PluginRuntime> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(runtime);
        I18nProperties i18n = new I18nProperties();
        SiteAwareMessageSource site = new SiteAwareMessageSource(resolver, layout, i18n, provider);
        OperatorMessageSource operator = new OperatorMessageSource(layout, i18n, provider);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("localeResolver", new OperatorLocaleResolver(List.of("en", "es"), "en"));
        messages = new MessagesImpl(site, operator, beans.getBeanProvider(LocaleResolver.class));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        resolver.clear();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void siteTextComesFromTheNamedSiteFirstThenThePluginBundle() {
        assertEquals("Write to Acme", messages.site("acme", Locale.ENGLISH, "contact.title", "fb"));
        assertEquals("Escribe a Acme", messages.site("acme", Locale.forLanguageTag("es"), "contact.title", "fb"));
        assertEquals("Thanks, Ana!", messages.site("acme", Locale.ENGLISH, "contact.sent", "fb", "Ana"),
                "a plugin key the site does not override, with arguments");
        assertEquals("PLUGIN DEFAULT", messages.site("gamma", Locale.ENGLISH, "contact.title", "fb"),
                "a site with no bundle of its own still gets the plugin's text");
    }

    @Test
    void theNamedSiteWinsOverTheSiteBoundToTheThread() {
        resolver.bind(new Site("acme", "Acme", List.of(), Locale.ENGLISH, List.of(), null, null));
        assertEquals("Write to Beta", messages.site("beta", Locale.ENGLISH, "contact.title", "fb"));
    }

    @Test
    void aMissingKeyGivesTheFallbackOrTheCodeAndArgumentsApplyToTheFallback() {
        assertEquals("Hello, Bo", messages.site("acme", Locale.ENGLISH, "no.such", "Hello, {0}", "Bo"));
        assertEquals("no.such", messages.site("acme", Locale.ENGLISH, "no.such", null));
    }

    @Test
    void anUnsafeSiteIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> messages.site("../acme", Locale.ENGLISH, "x", "y"));
        assertThrows(IllegalArgumentException.class, () -> messages.site(null, Locale.ENGLISH, "x", "y"));
    }

    private static void onConsoleRequest(String languageCookie) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__admin/contact");
        request.setRequestURI("/__admin/contact");
        if (languageCookie != null) {
            request.setCookies(new Cookie("runway-op-lang", languageCookie));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void consoleTextFollowsTheOperatorsUiLanguage() {
        onConsoleRequest("es");
        assertEquals("Formulario de contacto", messages.console("contact.console.title", "fb"));
        assertEquals("Copias de seguridad", messages.console("op.backups.title", "fb"), "core console strings too");
        onConsoleRequest(null);
        assertEquals("Contact form", messages.console("contact.console.title", "fb"));
    }

    @Test
    void consoleTextOffARequestIsTheFallbackOrTheCode() {
        assertEquals("fb", messages.console("contact.console.title", "fb"));
        assertEquals("contact.console.title", messages.console("contact.console.title", null));
    }
}
