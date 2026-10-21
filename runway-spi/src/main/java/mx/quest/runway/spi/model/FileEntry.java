package mx.quest.runway.spi.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry of a plugin's per-site file subtree, as {@link mx.quest.runway.spi.service.PluginSiteFiles} reports
 * it. Immutable.
 *
 * @param name         the entry's own name (one path segment, never {@code null})
 * @param directory    whether it is a folder
 * @param size         the size in bytes; {@code 0} for a folder
 * @param lastModified the last-modified time ({@code null} normalized to {@link Instant#EPOCH})
 * @since SPI 0.5.0
 */
public record FileEntry(String name, boolean directory, long size, Instant lastModified) {

    public FileEntry {
        Objects.requireNonNull(name, "name");
        size = directory ? 0 : Math.max(0, size);
        lastModified = lastModified == null ? Instant.EPOCH : lastModified;
    }
}
