package mx.quest.runway.spi.service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import mx.quest.runway.spi.model.FileEntry;

/**
 * The calling plugin's own <b>per-site file subtree</b>, {@code sites/<siteId>/plugins/<pluginId>/}: the place a
 * plugin keeps the data it generates for a site (form submissions, blog entries, logs of visitor searches). The
 * folder lives inside the site's folder, so a full backup carries it and a deleted site takes it along.
 *
 * <h2>How a plugin gets one</h2>
 * By ordinary Spring injection into a class from the plugin's <b>own jar</b>, exactly like
 * {@link PluginSettings}: the host binds the instance to the plugin whose jar the injecting class was loaded from.
 * No method takes a plugin id, so a plugin can only ever reach its own subtree. Injection from a {@code lib/}
 * class, from kernel code, or through a bare {@code getBean} fails fast.
 *
 * <h2>Confinement</h2>
 * Every path is relative to the subtree root and uses {@code /}. An absolute path, a backslash, an empty segment,
 * a {@code .} or {@code ..} segment, or a control character throws {@link IllegalArgumentException} before any
 * I/O. Beyond that lexical rule, every operation checks the <b>real</b> (symbolic-link-resolved) path: a symbolic
 * link planted inside the subtree, or a link standing in for the subtree itself, can never carry a read or a write
 * outside {@code plugins/<pluginId>/} (to the site's {@code users.yml}, another plugin's data, or another site);
 * such an operation throws {@link IllegalArgumentException}. A symbolic link at the leaf reads as absent and is
 * never listed. The plugin's settings file ({@code plugins/<pluginId>.yml}) is a <em>sibling</em> of the subtree,
 * not inside it, so it cannot be written through this service.
 *
 * <h2>Limits &amp; failure</h2>
 * <ul>
 *   <li>One write is capped by {@code runway.plugins.site-files.max-write-bytes} (default 16 MiB): over the cap,
 *       {@link IllegalArgumentException} and nothing is written. {@link #readText} refuses a file over the same
 *       cap with an {@link IOException}, so a large file placed by hand is never read whole into the heap.</li>
 *   <li>{@link #list} returns at most 10,000 entries (the first by name) and logs when it truncates.</li>
 *   <li>An unsafe site id throws {@link IllegalArgumentException}; a well-formed id of a site that does not exist
 *       simply has no files (reads are empty), and a write for it fails with {@link IOException} rather than
 *       creating the site's folder &mdash; so a late write for a deleted site cannot leave a stray folder that a
 *       new site with the same id would inherit.</li>
 * </ul>
 *
 * <h2>Concurrency and freshness</h2>
 * Thread-safe. Each write is atomic (temporary file, {@code fsync}, rename), so a reader never sees a torn file;
 * a <b>read-modify-write</b> sequence (append a line) must be serialized by the plugin itself. Writing a file
 * announces nothing: a plugin whose files shape routable content tells the host afterwards (the content-change
 * service of a later SPI level).
 *
 * <h2>Personal data</h2>
 * A file that holds visitor or user input (names, emails, messages, search terms) must be declared in
 * {@code plugin.yml} under {@code siteData.personal}; a <b>transfer copy</b> of the site leaves it out. See the
 * plugin development guide.
 *
 * @since SPI 0.5.0
 */
public interface PluginSiteFiles {

    /**
     * Reads a file as UTF-8 text.
     *
     * @return the text; empty when the file is absent, or the leaf is a directory or a symbolic link
     * @throws IllegalArgumentException if {@code siteId} or {@code relPath} is unsafe, or the path escapes the
     *                                  subtree through a symbolic link
     * @throws IOException              if the file is larger than the write cap, or cannot be read — including when
     *                                  whether it exists cannot be told (a folder that cannot be searched): only a
     *                                  file that is certainly absent answers empty
     */
    Optional<String> readText(String siteId, String relPath) throws IOException;

    /**
     * Atomically writes {@code text} (UTF-8), creating parent folders as needed and replacing any existing file.
     *
     * @throws IllegalArgumentException if a path is unsafe or escapes the subtree, or the encoded text is over the
     *                                  write cap (nothing is written)
     * @throws IOException              if the site does not exist, the leaf is a directory, or the write fails
     */
    void writeText(String siteId, String relPath, String text) throws IOException;

    /**
     * Deletes one regular file.
     *
     * @return {@code true} if a file was deleted; {@code false} when it was absent, a directory or a symbolic link
     * @throws IllegalArgumentException if a path is unsafe or escapes the subtree
     * @throws IOException              if the deletion fails
     */
    boolean delete(String siteId, String relPath) throws IOException;

    /**
     * Deletes a folder and everything in it (or a single regular file). Symbolic links inside it are removed as
     * links and <b>never followed</b>, so nothing outside the folder is touched. {@code relDir} must name
     * something below the root: the subtree root itself cannot be deleted this way.
     *
     * @return {@code true} if anything was deleted; {@code false} when it was absent or is a symbolic link
     * @throws IllegalArgumentException if a path is unsafe or escapes the subtree
     * @throws IOException              if a deletion fails (what was already deleted stays deleted)
     */
    boolean deleteTree(String siteId, String relDir) throws IOException;

    /**
     * The direct children of a folder, sorted by name; symbolic links are omitted. {@code relDir} may be
     * {@code ""} for the subtree root.
     *
     * @return the entries; empty when the folder is absent, is a file, or is a symbolic link
     * @throws IllegalArgumentException if a path is unsafe or escapes the subtree
     * @throws IOException              if the folder cannot be read, or cannot be searched to tell whether it is there
     */
    List<FileEntry> list(String siteId, String relDir) throws IOException;

    /**
     * What is at {@code relPath}: a regular file or a folder.
     *
     * @return the entry; empty when absent, a symbolic link, or another kind of file
     * @throws IllegalArgumentException if a path is unsafe or escapes the subtree
     * @throws IOException              if its attributes cannot be read, or its folder cannot be searched to tell
     *                                  whether it is there
     */
    Optional<FileEntry> stat(String siteId, String relPath) throws IOException;
}
