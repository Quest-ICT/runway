package mx.quest.runway.web;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jetty.ConfigurableJettyWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps slow clients from holding request threads: a minimum rate for request bodies ({@code
 * runway.http.min-request-data-rate}, bytes per second; Settings → Server settings → Request limits), applied to every
 * connector Jetty has when the server is built.
 *
 * <p><b>Why.</b> A servlet reads a request body on a request thread and blocks while it waits. Jetty's idle timeout
 * limits one stalled read, not the whole body, so a client that sends a byte inside every idle interval kept a thread
 * for as long as it liked, and a few of them (with uploads allowed up to 256 MB) could take the pool. With a minimum
 * rate Jetty answers {@code 408 Request Timeout} as soon as the body has arrived more slowly than the rate since its
 * first byte; time the application spends before reading does not count, because the rate is measured on what has
 * arrived. Measured on Jetty 12.1.8: a client sending one byte a second against 1 KiB/s was cut after about two
 * seconds, and a server pausing three seconds before reading a body that arrived at once still answered {@code 200}.
 *
 * <p><b>What it does not cover.</b> Slow header senders cost a connection, not a thread (Jetty parses headers without
 * one); a proxy's connection cap is the limit there. A client that reads a response slowly is bounded by the
 * connection idle timeout ({@code server.jetty.connection-idle-timeout}), which ends a write that cannot proceed for
 * that long; Jetty's response-rate minimum was measured not to act while a write is blocked, so it is not used.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SlowClientConfig.HttpLimitProperties.class)
public class SlowClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SlowClientConfig.class);

    @Bean
    public WebServerFactoryCustomizer<ConfigurableJettyWebServerFactory> slowClientJettyCustomizer(
            HttpLimitProperties properties) {
        long rate = Math.max(0, properties.getMinRequestDataRate());
        return factory -> factory.addServerCustomizers(server -> apply(server, rate));
    }

    /**
     * Sets the minimum request data rate on every HTTP configuration of {@code server}'s connectors.
     *
     * @param bytesPerSecond the rate; 0 turns the check off
     * @return how many HTTP configurations were changed
     */
    static int apply(Server server, long bytesPerSecond) {
        int changed = 0;
        for (Connector connector : server.getConnectors()) {
            for (ConnectionFactory factory : connector.getConnectionFactories()) {
                if (factory instanceof HttpConfiguration.ConnectionFactory http) {
                    http.getHttpConfiguration().setMinRequestDataRate(bytesPerSecond);
                    changed++;
                }
            }
        }
        if (bytesPerSecond > 0) {
            log.info("request bodies slower than {} bytes/s are refused (runway.http.min-request-data-rate)",
                    bytesPerSecond);
        } else {
            log.warn("runway.http.min-request-data-rate is 0: a client sending a request body slowly can hold a "
                    + "request thread for as long as it keeps sending");
        }
        return changed;
    }

    /** Bound {@code runway.http.*}: limits on how slowly a client may talk to Runway. */
    @ConfigurationProperties("runway.http")
    public static class HttpLimitProperties {

        /** The slowest a request body may arrive, in bytes per second. Default 4096; 0 or less turns the check off. */
        private long minRequestDataRate = 4096;

        public long getMinRequestDataRate() {
            return minRequestDataRate;
        }

        public void setMinRequestDataRate(long minRequestDataRate) {
            this.minRequestDataRate = minRequestDataRate;
        }
    }
}
