package mx.quest.runway.web;

import java.util.Collection;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jetty.ConfigurableJettyWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Raises Jetty's form-post parsing limits so the tenant <b>Messages</b> editor can be saved on a large site.
 *
 * <p>That editor submits a site's ENTIRE message grid as one {@code application/x-www-form-urlencoded} form —
 * a hidden {@code keep} + {@code key} and one {@code value_<locale>} input per locale, per message key. Jetty's
 * defaults are {@code maxFormKeys=1000} and {@code maxFormContentSize≈200 KB}; a site with a few hundred keys
 * across several locales blows past both (e.g. 730 keys × 3 locales ≈ 3,650 fields and ≈200 KB). Jetty then
 * aborts the request — <b>resetting the stream</b>, which a browser reports as {@code ERR_HTTP2_PROTOCOL_ERROR}
 * and no server-side exception is logged, so the save silently fails.
 *
 * <p>The caps below are generous enough for very large all-locale grids yet still bounded (so a form is never
 * unbounded memory). The Messages endpoint is {@code CONFIGURE_SITE}-gated and the per-IP edge guard remains
 * the abuse backstop. <b>They apply to signed-in callers only</b>: the container parses a form when the
 * CSRF check first asks for {@code _csrf}, before authorization, so a caller who is not signed in is held to the
 * pre-authentication budget ({@code runway.endpoints.max-form-bytes}, 256 KiB) by
 * {@link mx.quest.runway.endpoint.PreAuthBodyLimitFilter} in every realm's security chain — the 16 MiB below was
 * reachable by any visitor on any path (sign-in included) until then. Mirrors {@link TlsConfig}'s
 * {@code WebServerFactoryCustomizer} pattern; the limit is applied to every servlet context in the server
 * (the app context — and, when the HTTP→HTTPS redirect wraps it, the nested one is still reached).
 */
@Component
public class JettyFormLimitsConfig implements WebServerFactoryCustomizer<ConfigurableJettyWebServerFactory> {

    /** Enough for a very large site's whole grid (thousands of keys × locales); still bounded. */
    static final int MAX_FORM_KEYS = 100_000;

    /** 16 MiB — comfortably above any realistic all-locales message grid. */
    static final int MAX_FORM_CONTENT_BYTES = 16 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(JettyFormLimitsConfig.class);

    @Override
    public void customize(ConfigurableJettyWebServerFactory factory) {
        factory.addServerCustomizers(JettyFormLimitsConfig::applyFormLimits);
    }

    /** Package-visible for unit testing: raise the form caps on every servlet context in {@code server}. */
    static void applyFormLimits(Server server) {
        Collection<ServletContextHandler> contexts = server.getContainedBeans(ServletContextHandler.class);
        for (ServletContextHandler ctx : contexts) {
            ctx.setMaxFormKeys(MAX_FORM_KEYS);
            ctx.setMaxFormContentSize(MAX_FORM_CONTENT_BYTES);
        }
        log.info("Jetty form limits raised to maxFormKeys={}, maxFormContentSize={} bytes on {} context(s) — the "
                + "Messages editor posts a whole site's message grid as one form", MAX_FORM_KEYS,
                MAX_FORM_CONTENT_BYTES, contexts.size());
    }
}
