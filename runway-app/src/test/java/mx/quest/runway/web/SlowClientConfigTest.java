package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

/**
 * The minimum request data rate, against a real Jetty: a body trickled in is cut with {@code 408} instead of holding a
 * request thread, and a server that is slow to start reading is not blamed for it. Guards the behaviour across Jetty
 * upgrades, since the rate check is Jetty's.
 */
class SlowClientConfigTest {

    /** The bean, as Spring binds and applies it: the property, the clamp, and every connector of Boot's own Jetty. */
    @Test
    void theBoundPropertyReachesEveryConnectorOfTheServerBootBuilds() throws Exception {
        assertThat(rateOnBootsServer("1024")).containsOnly(1024L).isNotEmpty();
        assertThat(rateOnBootsServer("-5")).as("a negative value is clamped to off").containsOnly(0L);
    }

    private java.util.List<Long> rateOnBootsServer(String configured) throws Exception {
        SlowClientConfig.HttpLimitProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("runway.http.min-request-data-rate", configured)))
                .bind("runway.http", SlowClientConfig.HttpLimitProperties.class).get();
        JettyServletWebServerFactory factory = new JettyServletWebServerFactory(0);
        new SlowClientConfig().slowClientJettyCustomizer(properties).customize(factory);
        WebServer web = factory.getWebServer();
        web.start();
        try {
            java.util.List<Long> rates = new java.util.ArrayList<>();
            for (org.eclipse.jetty.server.Connector connector : ((JettyWebServer) web).getServer().getConnectors()) {
                for (org.eclipse.jetty.server.ConnectionFactory f : connector.getConnectionFactories()) {
                    if (f instanceof org.eclipse.jetty.server.HttpConfiguration.ConnectionFactory http) {
                        rates.add(http.getHttpConfiguration().getMinRequestDataRate());
                    }
                }
            }
            return rates;
        } finally {
            web.stop();
        }
    }

    private Server server;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    /** A server whose servlet reads the whole body after {@code pauseMillis}, with the rate applied by the config. */
    private int start(long bytesPerSecond, long pauseMillis) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());
        connector.setPort(0);
        connector.setIdleTimeout(30_000);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                response.getWriter().write("read " + request.getInputStream().readAllBytes().length);
            }
        }), "/*");
        server.setHandler(context);
        assertThat(SlowClientConfig.apply(server, bytesPerSecond)).isEqualTo(1);
        server.start();
        return connector.getLocalPort();
    }

    private static String statusLine(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        return response.isEmpty() ? "" : response.substring(0, response.indexOf("\r\n"));
    }

    @Test
    void aTrickledBodyIsCutWithRequestTimeout() throws Exception {
        int port = start(1024, 0);
        long started = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try {
                for (int i = 0; i < 20; i++) { // one byte a second: far below 1 KiB/s
                    Thread.sleep(1000);
                    out.write('a');
                    out.flush();
                }
            } catch (IOException closedByServer) {
                // expected: Jetty answered and closed while the client was still trickling
            }
            assertThat(statusLine(socket)).isEqualTo("HTTP/1.1 408 Request Timeout");
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void aServerSlowToReadIsNotBlamed() throws Exception {
        int port = start(1024, 3000);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\nConnection: close\r\n\r\n"
                    + "a".repeat(100)).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(statusLine(socket)).isEqualTo("HTTP/1.1 200 OK");
        }
    }

    @Test
    void theDefaultIsFourKilobytesPerSecond() {
        assertThat(new SlowClientConfig.HttpLimitProperties().getMinRequestDataRate()).isEqualTo(4096);
    }

    @Test
    void zeroTurnsTheCheckOff() throws Exception {
        int port = start(0, 0);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int i = 0; i < 3; i++) { // one byte a second, accepted when the check is off
                Thread.sleep(1000);
                out.write('a');
                out.flush();
            }
            assertThat(statusLine(socket)).isEqualTo("HTTP/1.1 200 OK");
        }
    }
}
