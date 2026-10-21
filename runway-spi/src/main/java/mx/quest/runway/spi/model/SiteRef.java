package mx.quest.runway.spi.model;

import java.util.Objects;

/**
 * A minimal, stable reference to a site (tenant) — just its identity. Used where an SPI needs
 * to name the site it is acting for without the fuller {@link SiteView} (for example a
 * {@link mx.quest.runway.spi.BackupParticipant} exporting a site's data, or the site behind a
 * routed request).
 *
 * @param id the site's stable identifier (never {@code null}); the same value the host uses to
 *           scope the site's folder, cache keys, and backups
 */
public record SiteRef(String id) {

    public SiteRef {
        Objects.requireNonNull(id, "id");
    }
}
