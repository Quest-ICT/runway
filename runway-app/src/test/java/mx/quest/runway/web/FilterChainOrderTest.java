package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * Asserts the <b>actual</b> resolved servlet-filter registration orders match {@link FilterOrder} — not the
 * constants against themselves, but the real {@code FilterRegistrationBean} orders and the Spring Security
 * proxy's order (from {@code spring.security.filter.order}). This is the regression guard for the
 * tenant-isolation linchpin: the security chain must stay <b>inner</b> of {@code SiteContextFilter}. Booting
 * in WIZARD mode (no app-master-id) is enough — every filter registration exists regardless of realm mode.
 */
@SpringBootTest
class FilterChainOrderTest {

    @Autowired
    private ApplicationContext context;

    @SuppressWarnings("rawtypes")
    private int orderOf(String beanName) {
        Map<String, AbstractFilterRegistrationBean> regs = context.getBeansOfType(AbstractFilterRegistrationBean.class);
        AbstractFilterRegistrationBean bean = regs.get(beanName);
        assertTrue(bean != null, "no filter registration bean named '" + beanName + "'; present: " + regs.keySet());
        return bean.getOrder();
    }

    @SuppressWarnings("rawtypes")
    private int securityProxyOrder() {
        // Boot registers the Spring Security FilterChainProxy via a DelegatingFilterProxyRegistrationBean,
        // whose order is spring.security.filter.order. Identify it by type, not a hard-coded bean name.
        Map<String, AbstractFilterRegistrationBean> regs = context.getBeansOfType(AbstractFilterRegistrationBean.class);
        return regs.values().stream()
                .filter(b -> b.getClass().getSimpleName().contains("DelegatingFilterProxy"))
                .mapToInt(AbstractFilterRegistrationBean::getOrder)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Spring Security DelegatingFilterProxy registration found"));
    }

    @Test
    void theFoldedFiltersKeepTheirLadderOrders() {
        assertEquals(FilterOrder.CORE_HEAD_INJECTION, orderOf("headInjectionFilterRegistration"));
        assertEquals(FilterOrder.CORE_SITE_CONTEXT, orderOf("siteContextFilterRegistration"));
        // The per-site health probe on the REAL resolved registrations, not just the constants.
        assertEquals(FilterOrder.CORE_SITE_HEALTH, orderOf("siteHealthFilterRegistration"));
        assertEquals(FilterOrder.CORE_SITE_RELOAD, orderOf("siteReloadFilterRegistration"));
        assertTrue(orderOf("siteReloadFilterRegistration") < orderOf("suspendedSiteFilterRegistration"));
        assertTrue(orderOf("siteContextFilterRegistration") < orderOf("siteHealthFilterRegistration"));
        assertTrue(orderOf("siteHealthFilterRegistration") < orderOf("suspendedSiteFilterRegistration"));
        assertEquals(FilterOrder.CORE_SUSPENDED_SITE, orderOf("suspendedSiteFilterRegistration"));
        assertEquals(FilterOrder.CORE_QUARANTINED_SITE, orderOf("quarantinedSiteFilterRegistration"));
    }

    @Test
    void theConsoleHostGateSitsOuterOfSecurityAndOfTheMultipartParser() {
        int gate = orderOf("consoleHostFilterRegistration");
        assertEquals(FilterOrder.CORE_CONSOLE_HOST, gate);
        assertTrue(gate < securityProxyOrder(), "the sign-in page must be refused before the security chain");
        assertTrue(gate < orderOf("multipartFilterBeforeSecurity"), "an upload to a refused host is not parsed");
    }

    @Test
    void theRouteFormCaptureSitsBetweenSiteBindingAndEveryFilterThatCouldReadTheBodyBeforeSecurity() {
        int capture = orderOf("routeFormCaptureFilterRegistration");
        assertEquals(FilterOrder.CORE_ROUTE_FORM_CAPTURE, capture);
        assertTrue(orderOf("siteContextFilterRegistration") < capture, "it needs the bound site");
        assertTrue(capture < orderOf("suspendedSiteFilterRegistration"), "outer of the tenant gates");
        assertTrue(capture < PluginFilterPlacement.window(mx.quest.runway.spi.FilterBand.TENANT).outer(),
                "outer of every plugin TENANT filter");
        assertTrue(capture < orderOf("multipartFilterBeforeSecurity"));
        assertTrue(capture < securityProxyOrder(), "the CSRF check must find the body already captured");
    }

    @Test
    void theSecurityProxyResolvesInnerOfTheTenantFiltersAtCoreSecurity() {
        int security = securityProxyOrder();
        // The exact value that keeps Spring Security INNER of site-context — reverting to Boot's -100 default
        // would run the chains before the site is bound and silently break tenant isolation.
        assertEquals(FilterOrder.CORE_SECURITY, security, "spring.security.filter.order drifted from CORE_SECURITY");
        assertTrue(orderOf("siteContextFilterRegistration") < security, "site-context must be OUTER of security");
        assertTrue(orderOf("quarantinedSiteFilterRegistration") < security, "tenant gates must be OUTER of security");
    }

    @Test
    void theClusteredSessionFilterSitsInnerOfTheProtectionFloorAndOuterOfSecurity() {
        // When runway.cluster.enabled=true, @EnableSpringHttpSession registers springSessionRepositoryFilter at
        // Spring Session's DEFAULT_ORDER (Integer.MIN_VALUE + 50). It must sit INNER of the O(1) reject floor
        // (CORE_PROTECTION) — so an abusive request is rejected before any session is touched — yet OUTER of the
        // Spring Security chain (CORE_SECURITY) — so the security chain reads the clustered session. This is a
        // constant relationship (the filter itself only exists when clustering is on), guarded here so a future
        // FilterOrder edit that crossed CORE_PROTECTION or CORE_SECURITY over the session filter is caught.
        int sessionFilterOrder = SessionRepositoryFilter.DEFAULT_ORDER;
        assertTrue(sessionFilterOrder > FilterOrder.CORE_PROTECTION,
                "the Spring Session filter (" + sessionFilterOrder + ") must be inner of CORE_PROTECTION ("
                        + FilterOrder.CORE_PROTECTION + ") so a rejected request never touches a session");
        assertTrue(sessionFilterOrder < FilterOrder.CORE_SECURITY,
                "the Spring Session filter (" + sessionFilterOrder + ") must be outer of CORE_SECURITY ("
                        + FilterOrder.CORE_SECURITY + ") so the security chain reads the clustered session");
    }

    @Test
    void theFullObservedOrderIsHeadThenTenantGatesThenSecurity() {
        int head = orderOf("headInjectionFilterRegistration");
        int site = orderOf("siteContextFilterRegistration");
        int suspended = orderOf("suspendedSiteFilterRegistration");
        int quarantined = orderOf("quarantinedSiteFilterRegistration");
        int security = securityProxyOrder();
        assertTrue(head < site, "head-injection (egress) must be outer of site-context");
        assertTrue(site < suspended && suspended < quarantined && quarantined < security,
                "expected site < suspended < quarantined < security; got "
                        + site + ", " + suspended + ", " + quarantined + ", " + security);
    }
}
