package mx.quest.database.spi;

import java.util.Optional;

/**
 * A provider's server settings as Spring binds them from the running application: register one as a bean with
 * {@code @ConfigurationProperties("runway.plugin.<provider-id>")} and turn it into {@link ProviderSettings} with
 * {@link #toSettings(Optional)}, passing the password from {@code PluginSettings.appSecret("password")} (it binds here
 * only in its stored {@code ENC(...)} form, so it has no field). Both providers bind this way, so the keys can never
 * differ from the ones {@link ProviderSettings} names.
 *
 * @since database 2.0.0
 */
public class ProviderSettingsProps {

    private String host;
    private String port;
    private String database;
    private String username;
    private String tls;
    private String caCertificate;
    private String collation;

    /**
     * The bound values with the given password.
     *
     * @param password the password in clear, or empty
     * @return the settings, a blank value as {@code null}
     */
    public ProviderSettings toSettings(Optional<String> password) {
        return ProviderSettings.read(key -> Optional.ofNullable(switch (key) {
            case ProviderSettings.HOST -> host;
            case ProviderSettings.PORT -> port;
            case ProviderSettings.DATABASE -> database;
            case ProviderSettings.USERNAME -> username;
            case ProviderSettings.TLS -> tls;
            case ProviderSettings.CA_CERTIFICATE -> caCertificate;
            case ProviderSettings.COLLATION -> collation;
            case ProviderSettings.PASSWORD -> password == null ? null : password.orElse(null);
            default -> null;
        }));
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTls() {
        return tls;
    }

    public void setTls(String tls) {
        this.tls = tls;
    }

    public String getCaCertificate() {
        return caCertificate;
    }

    public void setCaCertificate(String caCertificate) {
        this.caCertificate = caCertificate;
    }

    public String getCollation() {
        return collation;
    }

    public void setCollation(String collation) {
        this.collation = collation;
    }
}
