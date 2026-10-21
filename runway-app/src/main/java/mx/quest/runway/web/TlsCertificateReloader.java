package mx.quest.runway.web;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;

/**
 * Hot-reloads the TLS certificate when the keystore file changes on disk, so a renewal (e.g. an ACME client's
 * {@code --deploy-hook} rewriting the PKCS12) is picked up <b>without a restart</b>. Active only when
 * {@code runway.tls.enabled} and {@code runway.tls.watch-keystore} are on and the keystore path is set.
 *
 * <p>Runway does not build the TLS connector itself (Boot's {@code server.ssl.*} does — see {@link TlsConfig});
 * so this bean waits for the {@link WebServerInitializedEvent}, reaches into the running Jetty {@link Server}
 * for its {@link SslContextFactory}(ies), and starts a {@link KeystoreWatcher}. On a keystore change it calls
 * Jetty's {@link SslContextFactory#reload} — which re-reads the keystore and swaps the {@code SSLContext} live,
 * affecting only <em>new</em> connections. A reload that fails (a half-written or invalid keystore) is logged
 * and the previous certificate keeps serving — degrade, don't crash.
 */
public class TlsCertificateReloader {

    private static final Logger log = LoggerFactory.getLogger(TlsCertificateReloader.class);

    private final RunwayTlsProperties tls;
    private final java.util.concurrent.atomic.AtomicInteger reloadCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private KeystoreWatcher watcher;

    public TlsCertificateReloader(RunwayTlsProperties tls) {
        this.tls = tls;
    }

    /** How many times a keystore change has driven a successful certificate reload (for tests/diagnostics). */
    public int reloadCount() {
        return reloadCount.get();
    }

    @EventListener
    public void onWebServerReady(WebServerInitializedEvent event) {
        if (!tls.isEnabled() || !tls.isWatchKeystore() || tls.getKeystore() == null || tls.getKeystore().isBlank()) {
            return;
        }
        WebServer webServer = event.getWebServer();
        if (!(webServer instanceof JettyWebServer jetty)) {
            log.debug("TLS keystore watch skipped — not a Jetty web server");
            return;
        }
        Path keystore = Path.of(tls.getKeystore()).toAbsolutePath();
        if (!Files.isRegularFile(keystore)) {
            log.warn("TLS keystore watch skipped — keystore not found at {}", keystore);
            return;
        }
        Server server = jetty.getServer();
        watcher = new KeystoreWatcher(keystore, () -> reload(server));
        watcher.start();
    }

    /** Re-reads the keystore into every SSL connector's context factory. Best-effort, never throws. */
    private void reload(Server server) {
        int reloaded = 0;
        for (Connector connector : server.getConnectors()) {
            if (!(connector instanceof ServerConnector sc)) {
                continue;
            }
            SslConnectionFactory ssl = sc.getConnectionFactory(SslConnectionFactory.class);
            if (ssl == null) {
                continue;
            }
            SslContextFactory factory = ssl.getSslContextFactory();
            if (factory == null) {
                continue;
            }
            try {
                factory.reload(f -> { });
                reloaded++;
            } catch (Exception e) {
                log.warn("TLS certificate reload failed on connector '{}' — keeping the current certificate: {}",
                        sc.getName(), e.toString());
            }
        }
        if (reloaded > 0) {
            reloadCount.incrementAndGet();
            log.info("reloaded the TLS certificate from the keystore ({} connector(s)) after an on-disk change",
                    reloaded);
        }
    }

    @PreDestroy
    public void stop() {
        if (watcher != null) {
            watcher.stop();
        }
    }
}
