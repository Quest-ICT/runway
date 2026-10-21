package mx.quest.runway.web;

import java.nio.file.Path;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jetty.ConfigurableJettyWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires {@linkplain RunwayTlsProperties self-managed TLS}: the {@link TlsStartup} fail-fast/announce bean and
 * a Jetty customizer that (a) adds the optional plain-HTTP → HTTPS redirect connector wrapped by
 * {@link HttpToHttpsRedirectHandler}, and (b) applies the {@code sni-host-check} setting to the TLS listener.
 *
 * <p>The TLS listener itself is <b>not</b> built here: {@code runway.tls.*} feeds Boot's {@code server.ssl.*}
 * through property placeholders in {@code application.yml}, so Boot's own SSL wiring creates the HTTPS
 * connector. This class only adds what Boot does not have: the redirect side-listener (bypassing the servlet
 * pipeline entirely — see {@link HttpToHttpsRedirectHandler}) and the SNI toggle.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunwayTlsProperties.class)
public class TlsConfig {

    /** The redirect connector's name — how the handler recognizes plain-port traffic. */
    static final String REDIRECT_CONNECTOR_NAME = "runway-http-redirect";

    private static final Logger log = LoggerFactory.getLogger(TlsConfig.class);

    @Bean
    public TlsStartup tlsStartup(RunwayTlsProperties tls, Environment environment) {
        return new TlsStartup(tls, environment);
    }

    /**
     * Hot-reloads the certificate when the keystore file changes (renewal without a restart). Harmless when
     * TLS is off or the watch is disabled — it just does nothing on the {@code WebServerInitializedEvent}.
     */
    @Bean
    public TlsCertificateReloader tlsCertificateReloader(RunwayTlsProperties tls) {
        return new TlsCertificateReloader(tls);
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableJettyWebServerFactory> tlsJettyCustomizer(
            RunwayTlsProperties tls, Environment environment) {
        return factory -> factory.addServerCustomizers(server -> {
            if (tls.isEnabled() && !tls.isSniHostCheck()) {
                relaxSniHostCheck(server);
            }
            if (tls.getRedirectHttp().isEnabled()) {
                addRedirectConnector(server, tls, environment);
            }
        });
    }

    /** Adds the plain-HTTP listener and wraps the application handler so its traffic never reaches the app. */
    private static void addRedirectConnector(Server server, RunwayTlsProperties tls, Environment environment) {
        RunwayTlsProperties.RedirectHttp redirect = tls.getRedirectHttp();

        HttpConfiguration plain = new HttpConfiguration();
        plain.setSendServerVersion(false);
        plain.setSendXPoweredBy(false);
        ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory(plain));
        connector.setName(REDIRECT_CONNECTOR_NAME);
        connector.setPort(redirect.getPort());
        connector.setIdleTimeout(5_000); // redirects are one-shot; don't hold plain-port connections open
        server.addConnector(connector);

        server.setHandler(new HttpToHttpsRedirectHandler(server.getHandler(), REDIRECT_CONNECTOR_NAME,
                httpsPortSupplier(server, environment), redirect.getStatus(),
                Path.of(redirect.getAcmeChallengesDir())));
        log.info("HTTP→HTTPS redirect listener on port {} ({}; ACME webroot {})",
                redirect.getPort(), redirect.getStatus(), redirect.getAcmeChallengesDir());
    }

    /**
     * Resolves the HTTPS port per request from the <em>actual bound</em> application connector (correct even
     * for {@code server.port: 0} / test random ports), falling back to the configured value pre-start.
     */
    private static java.util.function.IntSupplier httpsPortSupplier(Server server, Environment environment) {
        int configured = Integer.parseInt(environment.getProperty("server.port", "8080"));
        return () -> {
            for (var connector : server.getConnectors()) {
                if (connector instanceof ServerConnector sc && !REDIRECT_CONNECTOR_NAME.equals(sc.getName())) {
                    int bound = sc.getLocalPort();
                    if (bound > 0) {
                        return bound;
                    }
                }
            }
            return configured;
        };
    }

    /** Turns off Jetty's SNI/Host consistency check (for re-encrypting proxies that connect by IP). */
    private static void relaxSniHostCheck(Server server) {
        for (var connector : server.getConnectors()) {
            HttpConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
            if (http == null) {
                continue;
            }
            SecureRequestCustomizer secure =
                    http.getHttpConfiguration().getCustomizer(SecureRequestCustomizer.class);
            if (secure != null) {
                secure.setSniHostCheck(false);
                log.warn("TLS SNI host-check DISABLED (runway.tls.sni-host-check=false) — only for "
                        + "re-encrypting proxies that connect by IP while forwarding the real Host");
            }
        }
    }
}
