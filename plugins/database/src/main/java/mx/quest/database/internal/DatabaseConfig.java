package mx.quest.database.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mx.quest.database.spi.ConnectionSettings.Tls;
import mx.quest.database.spi.ProviderSettings;

/**
 * The database plugin's settings after checking: what the pool, the names and the console screen use. Built once at
 * startup from {@link DatabaseProps} and the chosen provider's running settings, and by the connection test from a
 * provider's saved settings. A
 * value the plugin cannot use safely is refused with a reason rather than repaired, except the tuning numbers, which
 * are clamped to a working range.
 *
 * @param provider          {@code postgresql} or {@code mariadb}
 * @param namespace         the install's namespace ({@link Names#NAMESPACE})
 * @param host              the checked host: an RFC-1123 name, an IPv4 address or an IPv6 literal without brackets
 * @param port              1–65535, or 0 for the default
 * @param database          the database name
 * @param username          the account
 * @param password          the account's password in clear, possibly empty; never logged
 * @param tls               the TLS policy
 * @param caCertificate     a PEM certificate to verify the server against, or {@code null}
 * @param poolMax           the main pool's size
 * @param statementTimeout  a consumer statement's limit
 * @param backupBudgetBytes the compressed size a site's database backup may reach
 * @param collation         MariaDB's collation for consumer tables, or {@code null} for the server's best
 */
public record DatabaseConfig(String provider, String namespace, String host, int port, String database,
                             String username, String password, Tls tls, String caCertificate, int poolMax,
                             Duration statementTimeout, long backupBudgetBytes, String collation) {

    /** The providers this database plugin has SQL for. */
    public static final Set<String> PROVIDERS = Set.of("postgresql", "mariadb");

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$-]{1,63}");
    private static final Pattern COLLATION = Pattern.compile("[a-z0-9_]{1,64}");
    private static final int USERNAME_MAX = 128;
    private static final int PASSWORD_MAX = 1024;
    private static final int PEM_MAX = 64 * 1024;

    /** Clamped tuning ranges. */
    static final int POOL_MIN = 2;
    static final int POOL_MAX = 200;
    static final int TIMEOUT_MIN_S = 1;
    static final int TIMEOUT_MAX_S = 3600;
    static final int BUDGET_MIN_MIB = 1;
    static final int BUDGET_MAX_MIB = 4096;

    /** The secret is never part of the text. */
    @Override
    public String toString() {
        return "DatabaseConfig[provider=" + provider + ", namespace=" + namespace + ", host=" + host + ", port="
                + port + ", database=" + database + ", username=" + username + ", tls=" + tls + "]";
    }

    /**
     * Checks raw settings.
     *
     * @param props    the database plugin's own settings: namespace and limits (its provider choice is resolved by
     *                 the caller, {@link ProviderChoice})
     * @param provider the chosen provider's id
     * @param server   the chosen provider's server settings, the password in clear
     * @return the checked settings
     * @throws IllegalArgumentException naming the first setting that is missing or unusable; the message never
     *                                  holds the password
     */
    public static DatabaseConfig from(DatabaseProps props, String provider, ProviderSettings server) {
        String product = trimToNull(provider);
        if (product == null) {
            throw new IllegalArgumentException("no provider is chosen");
        }
        product = product.toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(product)) {
            throw new IllegalArgumentException("unknown provider: " + product);
        }
        String namespace = Names.namespace(Optional.ofNullable(trimToNull(props.getNamespace())).orElse("rw"));
        String host = checkHost(server.host());
        int port = port(server.port());
        String database = trimToNull(server.database());
        if (database == null || !DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException("the database name must be 1 to 63 letters, digits, '_', '$' or '-'");
        }
        String username = trimToNull(server.username());
        if (username == null || username.length() > USERNAME_MAX || hasControl(username)) {
            throw new IllegalArgumentException("the username must be 1 to " + USERNAME_MAX
                    + " characters, without control characters");
        }
        String secret = server.password() == null ? "" : server.password();
        if (secret.length() > PASSWORD_MAX || hasControl(secret)) {
            throw new IllegalArgumentException("the password is too long or holds control characters");
        }
        Tls tls = tls(server.tls());
        String ca = trimToNull(server.caCertificate());
        if (ca != null) {
            ca = checkPem(ca);
        }
        String collation = trimToNull(server.collation());
        if (collation != null && !COLLATION.matcher(collation).matches()) {
            throw new IllegalArgumentException("the collation must be a MariaDB collation name such as "
                    + "utf8mb4_uca1400_ai_ci");
        }
        int poolMax = clamp(props.getPoolMax(), 10, POOL_MIN, POOL_MAX);
        int timeout = clamp(props.getStatementTimeoutSeconds(), 10, TIMEOUT_MIN_S, TIMEOUT_MAX_S);
        int budget = clamp(props.getBackupBudgetMiB(), 128, BUDGET_MIN_MIB, BUDGET_MAX_MIB);
        return new DatabaseConfig(product, namespace, host, port, database, username, secret, tls, ca, poolMax,
                Duration.ofSeconds(timeout), budget * 1024L * 1024L, collation);
    }

    /** The port: blank for the product's default (0), else 1–65535. */
    static int port(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return 0;
        }
        try {
            int port = Integer.parseInt(v);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("the port must be between 1 and 65535, or blank for the default");
    }

    /** The TLS setting: {@code disable}, {@code require} or {@code verify-full} (the default). */
    static Tls tls(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return Tls.VERIFY_FULL;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "disable" -> Tls.DISABLE;
            case "require" -> Tls.REQUIRE;
            case "verify-full" -> Tls.VERIFY_FULL;
            default -> throw new IllegalArgumentException("tls must be disable, require or verify-full");
        };
    }

    /**
     * An RFC-1123 host name, an IPv4 address or an IPv6 literal (brackets allowed and removed). Anything that could
     * smuggle driver syntax — a list, a query, credentials, an address block, whitespace — is refused.
     */
    static String checkHost(String value) {
        String host = trimToNull(value);
        if (host == null) {
            throw new IllegalArgumentException("no host is set");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf(':') >= 0) {
            if (IPV6_CHARS.matcher(host).matches() && isIpv6(host)) {
                return host.toLowerCase(Locale.ROOT);
            }
            throw new IllegalArgumentException("the host is not a valid IPv6 address");
        }
        if (IPV4.matcher(host).matches()) {
            return host;
        }
        if (host.length() > 253 || host.endsWith(".")) {
            throw new IllegalArgumentException("the host is not a valid host name");
        }
        for (String label : host.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("the host is not a valid host name");
            }
        }
        if (host.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
            throw new IllegalArgumentException("the host is not a valid IPv4 address");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** An IPv6 literal: at most one {@code ::}, up to eight 1–4 digit groups, an optional trailing IPv4. */
    private static boolean isIpv6(String s) {
        int doubleColon = s.indexOf("::");
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0) {
            return false;
        }
        String[] parts = s.split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue; // part of a "::" or a leading/trailing colon next to it
            }
            if (i == parts.length - 1 && p.indexOf('.') >= 0) {
                if (!IPV4.matcher(p).matches()) {
                    return false;
                }
                groups += 2;
            } else if (p.length() > 4 || !p.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return false;
            } else {
                groups++;
            }
        }
        if (doubleColon < 0) {
            return groups == 8 && !s.startsWith(":") && !s.endsWith(":");
        }
        return groups < 8;
    }

    /** One or more PEM certificates and nothing else. */
    static String checkPem(String pem) {
        if (pem.length() > PEM_MAX) {
            throw new IllegalArgumentException("the CA certificate is too large");
        }
        String body = pem.replace("\r\n", "\n").strip();
        if (!body.startsWith("-----BEGIN CERTIFICATE-----") || !body.endsWith("-----END CERTIFICATE-----")) {
            throw new IllegalArgumentException("the CA certificate must be in PEM form (-----BEGIN CERTIFICATE-----)");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            if (factory.generateCertificates(new ByteArrayInputStream(body.getBytes(StandardCharsets.US_ASCII)))
                    .isEmpty()) {
                throw new IllegalArgumentException("the CA certificate holds no certificate");
            }
        } catch (CertificateException e) {
            throw new IllegalArgumentException("the CA certificate cannot be read as an X.509 certificate");
        }
        return body + "\n";
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    private static boolean hasControl(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
