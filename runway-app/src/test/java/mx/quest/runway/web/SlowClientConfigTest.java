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
 * request thread, whether the servlet reads the stream, form parameters or multipart parts, and a server that is slow
 * to start reading is not blamed for it. The check is {@link RequestBodyRateHandler}'s; Jetty's own is turned off.
 */
class SlowClientConfigTest {

    /** The bean, as Spring binds and applies it: the property, the clamp, and every connector of Boot's own Jetty. */
    @Test
    void theBoundPropertyReachesEveryConnectorOfTheServerBootBuilds() throws Exception {
        assertThat(rateOnBootsServer("1024")).as("Jetty's own check off on every connector; ours in Boot's chain")
                .containsOnly(0L, 1024L * 1_000_000).contains(0L, 1024L * 1_000_000);
        assertThat(rateOnBootsServer("-5")).as("a negative value is clamped to off, and no handler is installed")
                .containsOnly(0L).isNotEmpty();
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
            // The handler chain, outermost first: a RequestBodyRateHandler is reported as its rate x 1_000_000.
            for (org.eclipse.jetty.server.Handler h = ((JettyWebServer) web).getServer().getHandler(); h != null;
                    h = h instanceof org.eclipse.jetty.server.Handler.Wrapper w ? w.getHandler() : null) {
                if (h instanceof RequestBodyRateHandler rate) {
                    rates.add(rate.bytesPerSecond() * 1_000_000);
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

    /** How the servlet reads the body: the input stream (Jetty's own check), form parameters, or multipart parts. */
    private enum Body { STREAM, FORM, MULTIPART, SWALLOWED, ESCAPED }

    /** A server whose servlet reads the whole body after {@code pauseMillis}, with the rate applied by the config. */
    private int start(long bytesPerSecond, long pauseMillis) throws Exception {
        return start(bytesPerSecond, pauseMillis, Body.STREAM);
    }

    private int start(long bytesPerSecond, long pauseMillis, Body body) throws Exception {
        return start(bytesPerSecond, pauseMillis, body, RequestBodyRateHandler.CREDIT_SECONDS);
    }

    private int start(long bytesPerSecond, long pauseMillis, Body body, long creditSeconds) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());
        connector.setPort(0);
        connector.setIdleTimeout(30_000);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        ServletHolder holder = new ServletHolder(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String read = switch (body) {
                    case STREAM -> "read " + request.getInputStream().readAllBytes().length;
                    case FORM -> "a=" + request.getParameter("a").length();
                    case MULTIPART -> "parts " + request.getParts().size();
                    case SWALLOWED -> { // the read fails and the code carries on as if nothing happened
                        try {
                            request.getInputStream().readAllBytes();
                        } catch (IOException ignored) {
                            // swallowed on purpose
                        }
                        yield "fine";
                    }
                    case ESCAPED -> { // the failure escapes, as from a filter that reads the stream itself
                        request.getInputStream().readAllBytes();
                        yield "unreachable";
                    }
                };
                response.getWriter().write(read);
            }
        });
        if (body == Body.MULTIPART) {
            holder.getRegistration().setMultipartConfig(new jakarta.servlet.MultipartConfigElement(""));
        }
        context.addServlet(holder, "/*");
        server.setHandler(context);
        assertThat(SlowClientConfig.apply(server, bytesPerSecond)).isEqualTo(1);
        if (creditSeconds != RequestBodyRateHandler.CREDIT_SECONDS
                && server.getHandler() instanceof RequestBodyRateHandler installed) {
            RequestBodyRateHandler shortCredit = new RequestBodyRateHandler(bytesPerSecond, creditSeconds);
            shortCredit.setHandler(installed.getHandler());
            server.setHandler(shortCredit);
        }
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

    /** Sends {@code head}, then {@code drip} one byte a second until the server answers or 20 bytes are sent. */
    private static String drip(int port, String head) throws Exception {
        long started = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try {
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(1000);
                    out.write('a');
                    out.flush();
                }
            } catch (IOException closedByServer) {
                // expected: answered and closed while the client was still trickling
            }
            String status = statusLine(socket);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("cut long before the idle timeout")
                    .isLessThan(Duration.ofSeconds(15));
            return status;
        }
    }

    @Test
    void aTrickledFormIsCutToo() throws Exception {
        // 1.1.0: form parameters were read past Jetty's own check, and this request was held for the idle timeout.
        int port = start(1024, 0, Body.FORM);
        assertThat(drip(port, "POST / HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: 1000\r\nConnection: close\r\n\r\na=")).isEqualTo("HTTP/1.1 408 Request Timeout");
    }

    @Test
    void aTrickledMultipartBodyIsCutToo() throws Exception {
        int port = start(1024, 0, Body.MULTIPART);
        assertThat(drip(port, "POST / HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=XyZ\r\n"
                + "Content-Length: 1000\r\nConnection: close\r\n\r\n--XyZ\r\n"
                + "Content-Disposition: form-data; name=\"a\"\r\n\r\n")).isEqualTo("HTTP/1.1 408 Request Timeout");
    }

    @Test
    void aRefusedBodyIsAnswered408HoweverTheCodeHandledTheFailedRead() throws Exception {
        // Measured on 1.1.1-SNAPSHOT before this guard: a route reading the stream itself answered an empty 200.
        for (Body body : new Body[] {Body.SWALLOWED, Body.ESCAPED}) {
            int port = start(4096, 0, body);
            assertThat(drip(port, "POST / HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Length: 1000\r\nConnection: close\r\n\r\n")).as(body.name())
                    .isEqualTo("HTTP/1.1 408 Request Timeout");
            server.stop();
            server = null;
        }
    }

    @Test
    void aBodySentFastAtFirstCannotBuyALongDripLater() throws Exception {
        // 32 KiB at line speed, then a byte a second: averaged over the whole body (1.1.0, and this fix's first form)
        // that bought 32 s of dripping at 1 KiB/s, and with 16 MiB over an hour. The credit is capped (2 s here).
        int port = start(1024, 0, Body.FORM, 2);
        long started = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST / HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: 65536\r\nConnection: close\r\n\r\na=" + "b".repeat(32 * 1024))
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try {
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(1000);
                    out.write('c');
                    out.flush();
                }
            } catch (IOException closedByServer) {
                // expected
            }
            assertThat(statusLine(socket)).isEqualTo("HTTP/1.1 408 Request Timeout");
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aSmallFormWhoseOnlyPacketArrivesLateIsAccepted() throws Exception {
        // A network stall before the body: the body then arrives whole, so no thread is held by a slow client.
        int port = start(1024, 0, Body.FORM);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST / HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: 12\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(2500);
            out.write("a=1234567890".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(statusLine(socket)).isEqualTo("HTTP/1.1 200 OK");
        }
    }

    @Test
    void aLargeFormAtAGoodRateIsAccepted() throws Exception {
        int port = start(4096, 0, Body.FORM);
        int size = 64 * 1024;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST / HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: " + (size + 2) + "\r\nConnection: close\r\n\r\na=")
                    .getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < size / 4096; i++) { // 4 KiB every 100 ms: 40 KiB/s, ten times the minimum
                out.write("b".repeat(4096).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(100);
            }
            socket.setSoTimeout(10_000);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertThat(response).startsWith("HTTP/1.1 200 OK").endsWith("a=" + size);
        }
    }

    @Test
    void aFormServerSlowToReadIsNotBlamed() throws Exception {
        int port = start(1024, 3000, Body.FORM);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST / HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: 102\r\nConnection: close\r\n\r\na=" + "c".repeat(100))
                    .getBytes(StandardCharsets.US_ASCII));
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
