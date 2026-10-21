package mx.quest.runway.web;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * Fail-fast validation and the startup announcement for {@linkplain RunwayTlsProperties self-managed TLS}.
 *
 * <p>Runway must never start half-secured: if {@code runway.tls.enabled} is on but the keystore is missing,
 * unreadable, or has no password — or the HTTP→HTTPS redirect is on without TLS itself — startup <b>fails</b>
 * with a precise message instead of silently serving plain HTTP the operator believes is encrypted. (A wrong
 * password still fails a moment later when Jetty opens the keystore — also fatal, just with Jetty's message.)
 *
 * <p>On successful startup one clear line states where TLS terminates — locally at Jetty, or upstream at the
 * proxy — and on which port, so an operator reading the log never has to guess the topology.
 */
public class TlsStartup {

    /** The statuses the HTTP→HTTPS listener may answer with. Until 1.0.0 any number was sent as given. */
    static final java.util.Set<Integer> REDIRECT_STATUSES = java.util.Set.of(301, 302, 307, 308);

    private static final Logger log = LoggerFactory.getLogger(TlsStartup.class);

    private final RunwayTlsProperties tls;
    private final Environment environment;

    public TlsStartup(RunwayTlsProperties tls, Environment environment) {
        this.tls = Objects.requireNonNull(tls, "tls");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @PostConstruct
    void validate() {
        if (tls.getRedirectHttp().isEnabled() && !tls.isEnabled()) {
            throw new IllegalStateException("runway.tls.redirect-http.enabled requires runway.tls.enabled: "
                    + "the redirect sends clients to an HTTPS listener this server would not have");
        }
        int status = tls.getRedirectHttp().getStatus();
        if (tls.getRedirectHttp().isEnabled() && !REDIRECT_STATUSES.contains(status)) {
            throw new IllegalStateException("runway.tls.redirect-http.status is " + status
                    + ", which is not a redirect: use 301 (permanent), 308 (permanent, keeps the method), "
                    + "302 or 307");
        }
        if (!tls.isEnabled()) {
            return;
        }
        if (tls.getKeystore() == null || tls.getKeystore().isBlank()) {
            throw new IllegalStateException(
                    "runway.tls.enabled is on but runway.tls.keystore is empty — set the PKCS12 keystore path");
        }
        Path keystore = Path.of(tls.getKeystore()).toAbsolutePath().normalize();
        if (!Files.isReadable(keystore)) {
            throw new IllegalStateException("runway.tls.keystore is missing or unreadable: " + keystore
                    + " (working directory: " + Path.of("").toAbsolutePath() + ")");
        }
        if (tls.getKeystorePassword() == null || tls.getKeystorePassword().isBlank()) {
            throw new IllegalStateException("runway.tls.enabled is on but runway.tls.keystore-password is empty — "
                    + "set it in the external application.yml (chmod 600)");
        }
        // Actually open the keystore, so a corrupt file, a wrong password, or a missing key entry fails HERE
        // with a precise message — not a moment later inside Jetty's connector startup with a generic one.
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore store = KeyStore.getInstance(tls.getKeystoreType());
            store.load(in, tls.getKeystorePassword().toCharArray());
            if (!store.containsAlias(tls.getKeyAlias())) {
                throw new IllegalStateException("runway.tls.key-alias '" + tls.getKeyAlias()
                        + "' does not exist in " + keystore + " — list entries with: keytool -list -keystore "
                        + keystore + " -storetype " + tls.getKeystoreType());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("runway.tls.keystore " + keystore + " could not be opened as "
                    + tls.getKeystoreType() + " with the configured password: " + e.getMessage(), e);
        }
    }

    /** One clear line so the log never leaves the TLS topology ambiguous. */
    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        if (tls.isEnabled()) {
            RunwayTlsProperties.RedirectHttp redirect = tls.getRedirectHttp();
            log.info("TLS terminates HERE: HTTPS on port {} (keystore {}, type {}){}", port,
                    tls.getKeystore(), tls.getKeystoreType(),
                    redirect.isEnabled()
                            ? " — plain HTTP on port " + redirect.getPort() + " redirects with "
                              + redirect.getStatus() + " (ACME challenges exempt)"
                            : "");
        } else {
            log.info("TLS does NOT terminate here: plain HTTP on port {} — terminate TLS upstream at the "
                    + "reverse proxy (runway.proxy.trusted-cidrs gates its forwarded headers)", port);
        }
    }
}
