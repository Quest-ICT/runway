package mx.quest.runway.web;

import java.nio.file.Path;
import mx.quest.runway.storage.FileStamp;
import org.thymeleaf.cache.ICacheEntryValidity;

/**
 * The parsed-template cache validity of a template read from the data directory: the cached parse stays valid
 * exactly as long as its source files ({@code <name>.html} and its decoupled {@code <name>.th.xml}) are unchanged
 * on disk, as judged by a {@link FileStamp} taken when the template was resolved.
 *
 * <p>Why: the page ETag folds those files' <em>bytes</em> on every request, so it moves the moment a file is
 * edited on disk. Before this validity the parse cache did not, and a hand-edited template was served from the
 * previous parse under the new ETag — a client then stored the old body under the new validator and kept
 * revalidating it to {@code 304} even after the cache was cleared. Checking the files on each cache hit keeps the
 * cache ON (one {@code stat} per file per hit) while the body always comes from the version the ETag describes.
 *
 * <p>Thymeleaf calls {@link #isCacheStillValid()} on every hit and drops an entry that answers {@code false}, so
 * the next render re-reads the file. Each entry checks only its own files: an edit evicts nothing of any other
 * template, let alone another site's. The event-driven eviction in {@link TemplateCacheService} stays — it
 * frees memory at once on a console save and covers what no file stamp sees (a delete/restore of a whole site).
 */
public final class FileStampValidity implements ICacheEntryValidity {

    /** The decoupled-logic sibling Thymeleaf reads for {@code <name>.html}: {@code <name>.th.xml}. */
    private static final String HTML = ".html";
    private static final String DECOUPLED = ".th.xml";

    private final FileStamp stamp;

    private FileStampValidity(FileStamp stamp) {
        this.stamp = stamp;
    }

    /** A validity over {@code htmlFile} and its {@code .th.xml} sibling, stamped now (before the parse reads them). */
    public static FileStampValidity forTemplate(Path htmlFile) {
        String name = htmlFile.getFileName().toString();
        String base = name.endsWith(HTML) ? name.substring(0, name.length() - HTML.length()) : name;
        return new FileStampValidity(FileStamp.of(htmlFile, htmlFile.resolveSibling(base + DECOUPLED)));
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public boolean isCacheStillValid() {
        return stamp.isCurrent();
    }
}
