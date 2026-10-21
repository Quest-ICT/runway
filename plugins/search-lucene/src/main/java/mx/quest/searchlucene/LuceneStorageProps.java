package mx.quest.searchlucene;

import java.util.Locale;

/**
 * The plugin's <b>app-level</b> settings — where the index bytes live — bound from
 * {@code external/plugin-settings/search-lucene.yml} (keys under {@code runway.plugin.search-lucene.*}), the
 * plugin.yml {@code settings:} schema. Storage is a <b>platform</b> concern, not a tenant one: a filesystem path
 * a tenant could edit would be an isolation/abuse risk, so it is app-level and applied on <b>restart</b> (a
 * plugin reads its app-level settings through its own {@code @ConfigurationProperties}, never host machinery). The
 * binding is declared on the {@code @Bean} method ({@code searchLuceneStorageProps} in {@link SearchLuceneConfig}), so
 * the bean carries the plugin-id prefix every plugin bean name needs.
 *
 * <p>Field defaults mirror the schema defaults, so the plugin behaves sensibly (heap storage) when the file is
 * absent — degrade-don't-crash.
 *
 * @see Storage the two storage modes and their trade-offs
 */
public class LuceneStorageProps {

    /**
     * Where a per-site Lucene index is held.
     *
     * <ul>
     *   <li>{@link #HEAP} — an in-memory {@code ByteBuffersDirectory}: no disk footprint, but the index is
     *       rebuilt from the page store after every restart. The default; ideal for small/medium sites.</li>
     *   <li>{@link #DISK} — a memory-mapped {@code MMapDirectory} under {@link #getIndexRoot()}{@code /<siteId>}:
     *       survives restarts and lets the OS page large indexes in/out of memory. Choose for large corpora.</li>
     * </ul>
     */
    public enum Storage { HEAP, DISK }

    private Storage storage = Storage.HEAP;
    private String indexRoot = "./external/plugins/search-lucene/index";

    /** The configured storage mode; never {@code null} (defaults to {@link Storage#HEAP}). */
    public Storage getStorage() {
        return storage;
    }

    /**
     * Binds the {@code storage} setting. Tolerant of case and blanks: an unrecognized or blank value falls back
     * to {@link Storage#HEAP} rather than failing the context (degrade-don't-crash for a hand-edited file).
     */
    public void setStorage(String storage) {
        if (storage == null || storage.isBlank()) {
            this.storage = Storage.HEAP;
            return;
        }
        try {
            this.storage = Storage.valueOf(storage.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            this.storage = Storage.HEAP;
        }
    }

    /** The root folder for disk indexes (per-site subfolders are created under it); never blank. */
    public String getIndexRoot() {
        return indexRoot;
    }

    /** Binds the {@code indexRoot} setting; a blank value restores the default so the path is never empty. */
    public void setIndexRoot(String indexRoot) {
        this.indexRoot = (indexRoot == null || indexRoot.isBlank())
                ? "./external/plugins/search-lucene/index" : indexRoot.trim();
    }

    /** Whether disk (MMap) storage is selected. */
    public boolean isDisk() {
        return storage == Storage.DISK;
    }
}
