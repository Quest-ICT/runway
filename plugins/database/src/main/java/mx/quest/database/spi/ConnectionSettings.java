package mx.quest.database.spi;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * What a {@link DatabaseProvider} needs to build its {@code DataSource}: the server, the account and the TLS policy,
 * every value already checked by the database plugin. A provider uses them as given and adds only the driver
 * properties it pins itself; it never parses them into a URL beyond {@code scheme://host:port/database}.
 *
 * @param host            an RFC-1123 host name, an IPv4 address or an IPv6 literal (without brackets)
 * @param port            1–65535, or 0 for the product's default port
 * @param database        the database to connect to
 * @param username        the account
 * @param password        the account's password in clear; never logged ({@link #toString()} leaves it out)
 * @param tls             the TLS policy
 * @param caCertificate   a PEM file holding the CA (or the server certificate) to verify the server against when
 *                        {@code tls} is {@link Tls#VERIFY_FULL}; {@code null} for the JVM's trust store
 * @param connectTimeout  how long opening a connection may take
 * @param socketTimeout   how long a read may block; longer than any statement timeout the database plugin sets
 */
public record ConnectionSettings(String host, int port, String database, String username, String password,
                                 Tls tls, Path caCertificate, Duration connectTimeout, Duration socketTimeout) {

    /** The TLS policy, from the {@code tls} setting. */
    public enum Tls {
        /** No TLS: for a database on the same host or a private network the operator trusts. */
        DISABLE,
        /** Encrypt, but do not check who the server is. */
        REQUIRE,
        /** Encrypt, check the certificate chain and that the certificate names {@link #host()}. */
        VERIFY_FULL
    }

    public ConnectionSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(socketTimeout, "socketTimeout");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        password = password == null ? "" : password;
    }

    /** The port to use: {@link #port()}, or {@code defaultPort} when it is 0. */
    public int portOr(int defaultPort) {
        return port == 0 ? defaultPort : port;
    }

    /** Everything but the password. */
    @Override
    public String toString() {
        return "ConnectionSettings[host=" + host + ", port=" + port + ", database=" + database + ", username="
                + username + ", tls=" + tls + ", caCertificate=" + caCertificate + "]";
    }
}
