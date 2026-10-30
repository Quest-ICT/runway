package mx.quest.database.spi;

import java.util.Optional;
import java.util.function.Function;

/**
 * Where a provider's server is and how to reach it, as the operator set it on the provider plugin's own settings page
 * (since database 2.0.0). Raw values: the database plugin checks every one of them before use and reports the first
 * it cannot use, so a provider passes them on unchanged and never validates or repairs them itself.
 *
 * <p>A provider reads these from its own app-level settings, declared under the same keys as the components below.
 * {@link #read(Function)} does that from one lookup, so both providers read their settings the same way.
 *
 * @param host          the server's host name or address
 * @param port          the port, or blank for the product's default
 * @param database      the database to connect to
 * @param username      the account
 * @param password      the account's password in clear, possibly blank; never logged ({@link #toString()} leaves it
 *                      out)
 * @param tls           {@code verify-full}, {@code require} or {@code disable}; blank for {@code verify-full}
 * @param caCertificate a PEM certificate to verify the server against, or blank for the JVM's trust store
 * @param collation     MariaDB's collation for consumer tables, or blank for the server's best; always blank for a
 *                      product without the setting
 */
public record ProviderSettings(String host, String port, String database, String username, String password,
                               String tls, String caCertificate, String collation) {

    /** The setting keys, as both providers declare them in their {@code plugin.yml}. */
    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String DATABASE = "database";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String TLS = "tls";
    public static final String CA_CERTIFICATE = "caCertificate";
    public static final String COLLATION = "collation";

    /**
     * Reads every key through one lookup, a blank or missing value becoming {@code null}.
     *
     * @param setting answers a setting key with its value; for {@link #PASSWORD} the value in clear
     * @return the settings
     */
    public static ProviderSettings read(Function<String, Optional<String>> setting) {
        return new ProviderSettings(value(setting, HOST), value(setting, PORT), value(setting, DATABASE),
                value(setting, USERNAME), value(setting, PASSWORD), value(setting, TLS),
                value(setting, CA_CERTIFICATE), value(setting, COLLATION));
    }

    /** Whether nothing that names a server is set: the provider has not been set up yet. */
    public boolean isEmpty() {
        return blank(host) && blank(database) && blank(username);
    }

    /** Everything but the password. */
    @Override
    public String toString() {
        return "ProviderSettings[host=" + host + ", port=" + port + ", database=" + database + ", username="
                + username + ", tls=" + tls + ", caCertificate=" + (blank(caCertificate) ? "none" : "set")
                + ", collation=" + collation + "]";
    }

    private static String value(Function<String, Optional<String>> setting, String key) {
        Optional<String> v = setting.apply(key);
        return v == null ? null : v.filter(s -> !s.isBlank()).orElse(null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
