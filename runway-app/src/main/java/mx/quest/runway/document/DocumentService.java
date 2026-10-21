package mx.quest.runway.document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteStorageCeilings;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.UploadNames;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Manages a site's downloadable documents under {@code sites/<id>/static/doc/**} — list, upload, delete, and
 * <b>disable</b> (withhold a file from the public without deleting its bytes). Confined to the {@code doc/}
 * subtree (a {@code MANAGE_DOCUMENTS} holder must not reach {@code static/css}|{@code js}|{@code img}); the
 * shared {@link SiteFileStore} enforces the site boundary and traversal/symlink safety underneath.
 *
 * <p>The disabled set is side-state kept <em>outside</em> the served tree ({@code sites/<id>/doc-state.yml},
 * so it is never itself downloadable) and cached per site; a disabled path is 404'd by the public serving
 * guard while the file stays on disk, ready to re-enable.
 */
@Service
public class DocumentService {

    /** A generous per-document ceiling — large enough for real downloads, bounded so an upload can't run away. */
    public static final long MAX_DOC_BYTES = 25L * 1024 * 1024;

    private static final String DOC_SUBTREE = "static/doc";
    private static final String STATE_FILE = "doc-state.yml";
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final SiteFileStore files;
    private final StorageLayout layout;
    private final YamlStore yaml;
    private final SiteStorageCeilings ceilings;
    private final StoreCache<String, Set<String>> disabledCache = new StoreCache<>(256);

    public DocumentService(SiteFileStore files, StorageLayout layout, YamlStore yaml, SiteStorageCeilings ceilings) {
        this.files = files;
        this.layout = layout;
        this.yaml = yaml;
        this.ceilings = ceilings;
    }

    /** One document in the listing: its path under {@code doc/}, size in bytes, and whether it is disabled. */
    public record DocumentEntry(String path, long sizeBytes, boolean disabled) {

        /** A human-readable size (B / KB / MB) for display; the raw {@link #sizeBytes()} drives sorting. */
        public String displaySize() {
            if (sizeBytes < 1024) {
                return sizeBytes + " B";
            }
            if (sizeBytes < 1024 * 1024) {
                return String.format(java.util.Locale.ROOT, "%.1f KB", sizeBytes / 1024.0);
            }
            return String.format(java.util.Locale.ROOT, "%.1f MB", sizeBytes / (1024.0 * 1024));
        }
    }

    /** The persisted disabled-set (a plain list so the YAML is human-readable). */
    public record DisabledDocs(List<String> paths) {
        public DisabledDocs {
            paths = (paths == null) ? List.of() : List.copyOf(paths);
        }
    }

    /** The site's documents, sorted, each flagged with its current disabled state. */
    public List<DocumentEntry> list(String siteId) throws IOException {
        Set<String> disabled = disabled(siteId);
        return files.list(siteId, DOC_SUBTREE).stream()
                .map(rel -> new DocumentEntry(rel, files.size(siteId, DOC_SUBTREE + "/" + rel), disabled.contains(rel)))
                .toList();
    }

    /**
     * Stores an uploaded document under {@code doc/} using a sanitized <em>leaf</em> filename.
     *
     * @throws IllegalArgumentException if the filename is unusable or the upload exceeds {@link #MAX_DOC_BYTES}
     * @throws SiteStorageCeilings.OverCeiling if it would take the site's files over their storage ceiling (a
     *                                         document lives in {@code static/}, so it counts like a static file)
     * @throws IOException              if the write fails
     */
    public void add(String siteId, String filename, byte[] bytes) throws IOException {
        String rel = DOC_SUBTREE + "/" + UploadNames.safeLeaf(filename);
        ceilings.write(siteId, bytes.length, () -> files.size(siteId, rel),
                () -> files.write(siteId, rel, bytes, MAX_DOC_BYTES));
    }

    /** Deletes a document and clears any disabled flag on it. */
    public boolean delete(String siteId, String path) throws IOException {
        String rel = safeDocRel(path);
        boolean removed = files.delete(siteId, DOC_SUBTREE + "/" + rel);
        ceilings.changed(siteId);
        setDisabled(siteId, rel, false);
        return removed;
    }

    /** Enables/disables a document — a disabled file is withheld from the public but not deleted. */
    public void setDisabled(String siteId, String path, boolean disabled) throws IOException {
        String rel = safeDocRel(path);
        Set<String> set = new LinkedHashSet<>(disabled(siteId));
        boolean changed = disabled ? set.add(rel) : set.remove(rel);
        if (changed) {
            yaml.write(stateFile(siteId), new DisabledDocs(List.copyOf(set)));
            disabledCache.invalidate(siteId);
        }
    }

    /** Whether the document at {@code docRelPath} (relative to {@code doc/}) is currently withheld. */
    public boolean isDisabled(String siteId, String docRelPath) {
        return disabled(siteId).contains(docRelPath);
    }

    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        // Only a wholesale rewrite (delete/restore) changes doc-state.yml out of band; enable/disable self-evicts
        // (setDisabled above). Evicting on every kind — e.g. a suspend/resume — is a needless re-scan.
        if (event.isWholesaleReplacement()) {
            disabledCache.invalidate(event.siteId());
        }
    }

    private Set<String> disabled(String siteId) {
        return disabledCache.get(siteId, this::loadDisabled);
    }

    private Set<String> loadDisabled(String siteId) {
        try {
            return new LinkedHashSet<>(
                    yaml.read(stateFile(siteId), DisabledDocs.class).orElse(new DisabledDocs(List.of())).paths());
        } catch (IOException | RuntimeException e) {
            log.warn("could not read {} for site '{}': {}", STATE_FILE, siteId, e.toString());
            return Set.of();
        }
    }

    private Path stateFile(String siteId) {
        return layout.siteDir(siteId).resolve(STATE_FILE);
    }

    /** A document-relative path (from a listing) validated against traversal before it reaches the store. */
    private static String safeDocRel(String path) {
        String rel = path == null ? "" : path.strip();
        // Documents are a FLAT namespace under doc/. Reject any embedded '/' too (not just leading '/' and '..'):
        // a subdir-bearing name would break the flat invariant and the download URL, even though SiteFileStore
        // still keeps it inside the site folder.
        if (rel.startsWith("/") || rel.contains("/") || rel.contains("..")) {
            throw new IllegalArgumentException("invalid document path: " + path);
        }
        return validate(rel);
    }

    private static String validate(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("invalid document name");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '|' || c == '\\') {
                throw new IllegalArgumentException("invalid character in document name");
            }
        }
        return name;
    }
}
