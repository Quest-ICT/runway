package mx.quest.database.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The database plugin's app-level settings as bound from {@code runway.plugin.database.*} at startup
 * ({@code external/plugin-settings/database.yml}): the provider choice, the namespace and the limits. Raw values:
 * {@link DatabaseConfig#from} checks them. The server's own settings (host, account, password, TLS) belong to the
 * provider plugin since 2.0.0 ({@link mx.quest.database.spi.ProviderSettings}).
 *
 * <p>The 1.x connection keys are still bound, only so that a {@code database.yml} that still holds them can be
 * reported as moved ({@link #legacyKeys()}) rather than read as "nothing is set". They are never used to connect.
 */
public class DatabaseProps {
    private String provider = "auto";
    private String namespace = "rw";
    private Integer poolMax = 10;
    private Integer statementTimeoutSeconds = 10;
    private Integer backupBudgetMiB = 128;

    // 1.x keys, bound only to report that they moved (never used to connect).
    private String host;
    private String port;
    private String database;
    private String username;
    private String password;
    private String tls;
    private String caCertificate;
    private String collation;

    /** The 1.x connection keys still set in {@code database.yml}, in declaration order; empty when none is. */
    public List<String> legacyKeys() {
        List<String> keys = new ArrayList<>();
        add(keys, "host", host);
        add(keys, "port", port);
        add(keys, "database", database);
        add(keys, "username", username);
        add(keys, "password", password);
        add(keys, "tls", tls);
        add(keys, "caCertificate", caCertificate);
        add(keys, "collation", collation);
        return keys;
    }

    private static void add(List<String> keys, String key, String value) {
        if (value != null && !value.isBlank()) {
            keys.add(key);
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Integer getPoolMax() {
        return poolMax;
    }

    public void setPoolMax(Integer poolMax) {
        this.poolMax = poolMax;
    }

    public Integer getStatementTimeoutSeconds() {
        return statementTimeoutSeconds;
    }

    public void setStatementTimeoutSeconds(Integer statementTimeoutSeconds) {
        this.statementTimeoutSeconds = statementTimeoutSeconds;
    }

    public Integer getBackupBudgetMiB() {
        return backupBudgetMiB;
    }

    public void setBackupBudgetMiB(Integer backupBudgetMiB) {
        this.backupBudgetMiB = backupBudgetMiB;
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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
