package mx.quest.runway.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound {@code runway.tls.*} configuration — optional <b>self-managed TLS termination</b>. Default OFF: the
 * expected topology is plain HTTP behind a reverse proxy that terminates TLS (see {@link RunwayProxyProperties});
 * enabling this makes the embedded Jetty terminate TLS itself instead.
 *
 * <p>The keystore values feed Spring Boot's {@code server.ssl.*} via property placeholders in
 * {@code application.yml} (no programmatic rebinding), so Boot's own SSL wiring does the work; {@link TlsStartup}
 * fail-fasts on an inconsistent configuration before the server starts serving. The keystore password is a
 * secret — it belongs in the external {@code application.yml} (chmod 600), never the classpath copy.
 *
 * <p>{@link RedirectHttp} adds the optional plain-HTTP listener that 301-redirects everything to HTTPS
 * <em>except</em> {@code /.well-known/acme-challenge/} (served as plain files from {@link
 * RedirectHttp#getAcmeChallengesDir()}), so Let's Encrypt HTTP-01 issuance/renewal via a {@code --webroot}
 * client keeps working with the redirect on.
 */
@ConfigurationProperties("runway.tls")
public class RunwayTlsProperties {

    /** Whether the embedded server terminates TLS itself (default: off — terminate at the proxy). */
    private boolean enabled;

    /** Path to the PKCS12 keystore holding the server certificate + key (required when enabled). */
    private String keystore = "";

    /** The keystore password (required when enabled; a secret — external {@code application.yml} only). */
    private String keystorePassword = "";

    /** The keystore type (default {@code PKCS12}). */
    private String keystoreType = "PKCS12";

    /** The keystore entry (alias) holding the server key (default {@code runway}). */
    private String keyAlias = "runway";

    /**
     * Whether Jetty rejects HTTPS requests whose Host does not match the TLS SNI name (Jetty's secure
     * default). Turn off only for re-encrypting proxies that connect by IP while forwarding the real Host.
     */
    private boolean sniHostCheck = true;

    /**
     * Whether to watch the keystore file and hot-reload the certificate when it changes on disk (so a
     * renewal is picked up without a restart). Only takes effect when {@link #enabled} is on. Default: on.
     */
    private boolean watchKeystore = true;

    private final RedirectHttp redirectHttp = new RedirectHttp();

    /** The optional plain-HTTP → HTTPS redirect listener. */
    public static class RedirectHttp {

        /** Whether the extra plain-HTTP redirect listener is on (requires {@code runway.tls.enabled}). */
        private boolean enabled;

        /** The plain-HTTP port to listen on (default 80 — binding it needs {@code CAP_NET_BIND_SERVICE}). */
        private int port = 80;

        /** The redirect status: {@code 301} (default) or {@code 308} to preserve the request method. */
        private int status = 301;

        /**
         * Directory served as plain files under {@code /.well-known/acme-challenge/} (the ACME HTTP-01
         * webroot — point certbot/acme.sh here). Exempt from the redirect. <b>Ownership matters:</b> its
         * contents are served to anyone on the plain port, so only the ACME client may write here — never
         * the application or an untrusted user (the handler refuses symlinks and over-sized files, but the
         * directory's permissions are the operator's first line).
         */
        private String acmeChallengesDir = "./external/acme-challenges";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public String getAcmeChallengesDir() {
            return acmeChallengesDir;
        }

        public void setAcmeChallengesDir(String acmeChallengesDir) {
            this.acmeChallengesDir = acmeChallengesDir;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeystore() {
        return keystore;
    }

    public void setKeystore(String keystore) {
        this.keystore = keystore;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public String getKeystoreType() {
        return keystoreType;
    }

    public void setKeystoreType(String keystoreType) {
        this.keystoreType = keystoreType;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public void setKeyAlias(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    public boolean isSniHostCheck() {
        return sniHostCheck;
    }

    public void setSniHostCheck(boolean sniHostCheck) {
        this.sniHostCheck = sniHostCheck;
    }

    public boolean isWatchKeystore() {
        return watchKeystore;
    }

    public void setWatchKeystore(boolean watchKeystore) {
        this.watchKeystore = watchKeystore;
    }

    public RedirectHttp getRedirectHttp() {
        return redirectHttp;
    }
}
