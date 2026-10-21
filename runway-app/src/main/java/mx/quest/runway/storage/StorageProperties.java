package mx.quest.runway.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the storage layer, bound from {@code runway.storage.*}. Only {@link #external}
 * is normally set; the rest default relative to it (or to the deployment root) — see
 * {@link StorageLayout}.
 *
 * <p>{@code runway.storage.stats} and {@code runway.storage.logs} were removed before 1.0: the first was never
 * read (bandwidth is metered per site, in {@code sites/<id>/stats/}) and the second only labelled a folder the
 * kernel never wrote. A configuration that still sets either binds harmlessly (unknown keys are ignored) and
 * {@link RetiredStorageKeys} logs one startup warning naming it.
 */
@ConfigurationProperties("runway.storage")
public class StorageProperties {

    /** Root of the external, operator-editable folder (holds {@code application.yml}, sites, etc.). */
    private Path external = Path.of("external");

    /** Where per-site folders live. Default: {@code <external>/sites}. */
    private Path sites;

    /** Where per-site backup archives are written. Default: {@code backups} at the deployment root. */
    private Path backups;

    public Path getExternal() {
        return external;
    }

    public void setExternal(Path external) {
        this.external = external;
    }

    public Path getSites() {
        return sites;
    }

    public void setSites(Path sites) {
        this.sites = sites;
    }

    public Path getBackups() {
        return backups;
    }

    public void setBackups(Path backups) {
        this.backups = backups;
    }
}
