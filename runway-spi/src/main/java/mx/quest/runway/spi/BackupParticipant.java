package mx.quest.runway.spi;

import java.io.IOException;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.SiteRef;

/**
 * Lets a plugin's <b>per-site generated data</b> travel inside a site's backup — a search
 * index, carousel slides, a contact form's stored config. Code and assets belong to the
 * plugin (and are never copied into a site); but data a plugin <em>generates for a site</em>
 * belongs to that site, so it must be exported into and restored from the site's backup.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #export} writes this participant's data for one site into the host-provided
 *       {@link BackupWriter}, which scopes everything under this participant's namespace.</li>
 *   <li>{@link #importInto} reads it back during restore and must be <b>idempotent</b> —
 *       importing the same backup twice yields the same state — and must tolerate a missing
 *       entry (an older backup, or a feature unused when the backup was taken).</li>
 *   <li>{@link #key()} is the stable archive-namespace key; a duplicate among kernel contributions fails startup; a plugin's
 *       colliding contribution is refused (the later plugin's in load order) and reported (since SPI 0.4.0).</li>
 * </ul>
 *
 * <h2>Limits &amp; failure</h2>
 * An {@code export} failure — an exception of any kind — <b>fails the whole backup</b>: a backup is complete or it is
 * not a backup. The error names the participant and its plugin. An {@code importInto} that throws (a
 * {@code RuntimeException}, {@code LinkageError} or {@code IOException}) is contained: the site's files are still
 * restored, the failure is logged and counted, and the next participant runs. Entry names must be plain relative
 * paths — blank, absolute, backslashed or {@code ..} names are refused with {@code IllegalArgumentException}.
 * Transfer copies skip participants entirely.
 *
 * <p><b>Threading.</b> {@code export} and {@code importInto} run on the thread that performs the backup or the
 * restore &mdash; a console request's, or the scheduler's for an automatic backup &mdash; while the host holds that
 * site's backup lock: calls for one site never overlap, but calls for <em>different</em> sites can run at the same
 * time. An implementation must be stateless or synchronize whatever it shares across sites.
 */
public interface BackupParticipant {

    /**
     * This participant's stable, unique key — also its namespace within a site's backup
     * archive.
     *
     * @return a non-{@code null}, non-empty identifier, stable across releases
     */
    String key();

    /**
     * Exports this participant's data for the given site.
     *
     * @param out  the host-provided sink, scoped to this participant's namespace (never {@code null})
     * @param site the site whose data to export (never {@code null})
     * @throws IOException if writing fails
     */
    void export(BackupWriter out, SiteRef site) throws IOException;

    /**
     * Restores this participant's data for the given site. Must be idempotent and tolerate
     * absent entries.
     *
     * @param in   the host-provided source, scoped to this participant's namespace (never {@code null})
     * @param site the site whose data to restore (never {@code null})
     * @throws IOException if reading fails
     */
    void importInto(BackupReader in, SiteRef site) throws IOException;

    /**
     * The capabilities this participant declares, so the host can branch on data rather than
     * type. Defaults to {@link Capabilities#none()}.
     *
     * @return the declared capabilities (never {@code null})
     */
    default Capabilities capabilities() {
        return Capabilities.none();
    }
}
