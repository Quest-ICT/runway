package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

/**
 * Verifies the form-limit customizer actually reaches the servlet context and raises both caps — the whole
 * point being that the Messages editor's large all-locale form is no longer aborted by Jetty's defaults.
 */
class JettyFormLimitsConfigTest {

    @Test
    void raisesBothFormLimitsOnTheContainedServletContext() {
        Server server = new Server();
        ServletContextHandler ctx = new ServletContextHandler();
        server.setHandler(ctx); // registers ctx as a contained bean, exactly as Spring Boot's factory does

        JettyFormLimitsConfig.applyFormLimits(server);

        assertEquals(JettyFormLimitsConfig.MAX_FORM_KEYS, ctx.getMaxFormKeys(),
                "maxFormKeys must be raised so a whole-grid message post is not rejected");
        assertEquals(JettyFormLimitsConfig.MAX_FORM_CONTENT_BYTES, ctx.getMaxFormContentSize(),
                "maxFormContentSize must be raised above the ~200 KB default");
    }

    @Test
    void reachesAServletContextNestedUnderAWrappingHandler() {
        // Mirrors the TLS HTTP->HTTPS redirect case, where the servlet context is wrapped: getContainedBeans
        // recurses the bean tree, so the nested context must still be found and adjusted.
        Server server = new Server();
        ServletContextHandler ctx = new ServletContextHandler();
        org.eclipse.jetty.server.handler.gzip.GzipHandler wrapper =
                new org.eclipse.jetty.server.handler.gzip.GzipHandler(ctx);
        server.setHandler(wrapper);

        JettyFormLimitsConfig.applyFormLimits(server);

        assertEquals(JettyFormLimitsConfig.MAX_FORM_KEYS, ctx.getMaxFormKeys());
        assertEquals(JettyFormLimitsConfig.MAX_FORM_CONTENT_BYTES, ctx.getMaxFormContentSize());
    }
}
