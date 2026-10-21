package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.mail.PlatformMailer;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.validation.support.BindingAwareModelMap;

/**
 * The extracted operator-chrome seam ({@link OperatorShellModel}): {@code populate} sets exactly the
 * {@code op-layout} shell attributes, choosing the app chrome for an application-realm path and the admin chrome
 * for a {@code /__admin} tenant path — the same behaviour {@link OperatorModelAdvice} used to inline, so kernel
 * screens are unaffected by the extraction. A plugin reuses this same bean via its own advice.
 */
class OperatorShellModelTest {

    private OperatorShellModel shellModel(SiteResolver siteResolver) {
        return shellModel(siteResolver, true, true); // configured: no warnings by default
    }

    private OperatorShellModel shellModel(SiteResolver siteResolver, boolean cipherAvailable, boolean smtpConfigured) {
        RunningPlugins running = mock(RunningPlugins.class);
        when(running.installed()).thenReturn(List.of());
        AuthorizationService authz = mock(AuthorizationService.class);
        PermissionCatalog catalog = mock(PermissionCatalog.class);
        MenuRegistry menu = new MenuRegistry(running, authz, catalog);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.isAvailable()).thenReturn(cipherAvailable);
        PlatformMailer mailer = mock(PlatformMailer.class);
        when(mailer.isConfigured()).thenReturn(smtpConfigured);
        // No build-info in the test → the model falls back to a process-start-time asset version.
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
        when(buildProperties.getIfAvailable()).thenReturn(null);
        return new OperatorShellModel(menu, siteResolver, new OperatorProperties(), cipher, mailer, buildProperties);
    }

    private static Model populated(OperatorShellModel shell, String uri) {
        Model model = new BindingAwareModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        shell.populate(model, request, null);
        return model;
    }

    @Test
    void populatesTheAppRealmChromeForAnAppPath() {
        SiteResolver siteResolver = mock(SiteResolver.class);
        Model model = new BindingAwareModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__app/protection");
        request.setRequestURI("/__app/protection");

        shellModel(siteResolver).populate(model, request, null);

        assertEquals("application", model.getAttribute("operatorRealm"));
        assertEquals("operator/fragments/app-topbar", model.getAttribute("topbarFragment"));
        assertEquals("/__app/protection", model.getAttribute("currentPath"));
        assertEquals("/protection", model.getAttribute("appPath"), "app path stripped of the /__app prefix");
        assertNull(model.getAttribute("tenantSiteId"), "no bound tenant on the app realm");
        assertEquals("Runway", model.getAttribute("appName"));
        assertEquals("Runway", model.getAttribute("titleSuffix"), "app-realm tab title stays '· Runway'");
        assertEquals("dev", model.getAttribute("appVersion"), "no build-info in the test ⇒ 'dev' for the footer");
        assertEquals(List.of("en", "es"), model.getAttribute("operatorLanguages"));
        // Every attribute op-layout reads is present.
        for (String key : List.of("currentPath", "adminPath", "appPath", "operatorRealm", "topbarFragment",
                "principalName", "appName", "appVersion", "tenantSiteId", "titleSuffix", "assetVersion",
                "operatorLanguages", "uiLocale", "pluginMenu", "pluginSettings", "pluginSettingsMenu",
                "pluginActivityMenu")) {
            org.junit.jupiter.api.Assertions.assertTrue(model.containsAttribute(key), "missing shell attr: " + key);
        }
    }

    @Test
    void theFooterVersionSurfacesTheBuildVersionWhenBuildInfoIsPresent() {
        // With build-info the footer reads "Runway <build version>" (op-layout binds |appName appVersion|).
        java.util.Properties props = new java.util.Properties();
        props.setProperty("version", "0.2.1-beta9");
        BuildProperties bp = new BuildProperties(props);
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bp);
        RunningPlugins running = mock(RunningPlugins.class);
        when(running.installed()).thenReturn(List.of());
        MenuRegistry menu = new MenuRegistry(running, mock(AuthorizationService.class), mock(PermissionCatalog.class));
        OperatorShellModel shell = new OperatorShellModel(menu, mock(SiteResolver.class), new OperatorProperties(),
                mock(SecretCipher.class), mock(PlatformMailer.class), provider);

        Model model = populated(shell, "/__app");
        assertEquals("0.2.1-beta9", model.getAttribute("appVersion"), "the footer surfaces the build version");
    }

    @Test
    void populatesTheAdminRealmChromeAndBoundSiteForATenantPath() {
        Site site = mock(Site.class);
        when(site.id()).thenReturn("acme");
        SiteResolver siteResolver = mock(SiteResolver.class);
        when(siteResolver.current()).thenReturn(Optional.of(site));

        Model model = new BindingAwareModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__admin/protection");
        request.setRequestURI("/__admin/protection");

        shellModel(siteResolver).populate(model, request, null);

        assertEquals("tenant", model.getAttribute("operatorRealm"));
        assertEquals("operator/fragments/admin-topbar", model.getAttribute("topbarFragment"));
        assertEquals("/protection", model.getAttribute("adminPath"), "admin path stripped of the /__admin prefix");
        assertEquals("acme", model.getAttribute("tenantSiteId"), "the bound tenant id is set for the admin badge");
        assertEquals("acme", model.getAttribute("titleSuffix"),
                "the tenant-console tab title ends in the site id, not the platform name, so simultaneously-open "
                        + "site consoles are distinguishable by their browser tab");
    }

    @Test
    void theTenantTitleSuffixFallsBackToThePlatformNameWithoutAResolvedSite() {
        SiteResolver siteResolver = mock(SiteResolver.class);
        when(siteResolver.current()).thenReturn(Optional.empty());
        // Defensive: a tenant path that somehow renders without a resolved site keeps a sensible title suffix.
        Model model = populated(shellModel(siteResolver), "/__admin/pages");
        assertNull(model.getAttribute("tenantSiteId"), "no site resolved ⇒ no badge id");
        assertEquals("Runway", model.getAttribute("titleSuffix"), "falls back to the platform name");
    }

    @Test
    void appRealmSurfacesTheMisconfigurationWarningsWhenUnconfigured() {
        Model model = populated(shellModel(mock(SiteResolver.class), false, false), "/__app/sites");
        assertEquals(Boolean.TRUE, model.getAttribute("secretKeyMissing"), "no cipher ⇒ secret-key warning");
        assertEquals(Boolean.TRUE, model.getAttribute("smtpMissing"), "no platform SMTP ⇒ mail warning");
    }

    @Test
    void theWarningsClearOnceConfigured() {
        Model model = populated(shellModel(mock(SiteResolver.class), true, true), "/__app/sites");
        assertEquals(Boolean.FALSE, model.getAttribute("secretKeyMissing"));
        assertEquals(Boolean.FALSE, model.getAttribute("smtpMissing"));
    }

    @Test
    void theWarningsNeverShowOnTheTenantConsole() {
        SiteResolver siteResolver = mock(SiteResolver.class);
        when(siteResolver.current()).thenReturn(Optional.empty());
        // Even fully unconfigured, a tenant page must not carry the platform warnings (app-realm only).
        Model model = populated(shellModel(siteResolver, false, false), "/__admin/pages");
        assertEquals(Boolean.FALSE, model.getAttribute("secretKeyMissing"));
        assertEquals(Boolean.FALSE, model.getAttribute("smtpMissing"));
    }
}
