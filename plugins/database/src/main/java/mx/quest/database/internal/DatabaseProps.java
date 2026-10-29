package mx.quest.database.internal;

/**
 * The database plugin's app-level settings as bound from {@code runway.plugin.database.*} at startup
 * ({@code external/plugin-settings/database.yml}). Raw values: {@link DatabaseConfig#from} checks them. The password
 * is not here: it is read in clear through {@code PluginSettings.appSecret("password")}.
 */
public class DatabaseProps {

    private String provider;
    private String namespace = "rw";
    private String host;
    private Integer port;
    private String database;
    private String username;
    private String tls = "verify-full";
    private String caCertificate;
    private Integer poolMax = 10;
    private Integer statementTimeoutSeconds = 10;
    private Integer backupBudgetMiB = 128;
    private String collation;

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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
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

    public String getCollation() {
        return collation;
    }

    public void setCollation(String collation) {
        this.collation = collation;
    }
}
