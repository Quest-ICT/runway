package mx.quest.runway.site;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Reads (and writes) a site's <b>template parameters</b> ({@code sites/<id>/params.yml}): a flat
 * {@code key → value} map of static strings exposed to every page render as the {@code params} model
 * attribute, so a tenant template can read {@code ${params.key}} without editing markup. It is the site's
 * one place for values its templates need repeatedly — an analytics id, a contact address, a feature switch —
 * edited once instead of being scattered through pages.
 *
 * <p>The map is read on <b>every public page render</b> (the render model needs it), so it is cached per site;
 * the cache is evicted on {@link #save} and on any {@link SiteChangedEvent} (notably {@code DELETED} and
 * {@code RESTORED}, so a restored backup's params take effect at once). This mirrors {@code SeoConfigService}
 * and reuses the same per-site IO ({@link StorageLayout#siteDir(String)} + {@link YamlStore}) and cache
 * ({@link StoreCache}) machinery — no new IO path.
 *
 * <p><b>Tenant-scoped and fail-soft.</b> A site id resolves only its own {@code params.yml} through the
 * layout's {@linkplain StorageLayout#siteDir(String) safe-id} confinement, so one site can never read
 * another's file. An absent, unreadable, or malformed file degrades to an <b>empty map</b> rather than
 * failing a page render.
 *
 * <p><b>Static strings only.</b> Values are read as plain text (a YAML scalar — number, boolean or string —
 * is coerced to its string form; a nested map or list is rejected, as it could not be addressed as
 * {@code ${params.key}}). Dynamic values belong to template logic and the model the platform already
 * provides, never to tenant-authored expressions the platform evaluates. Since the render model exposes these
 * through Thymeleaf's default-escaping {@code ${...}}, a value is emitted as text, not markup.
 */
@Service
public class SiteParamsService {

    private static final Logger log = LoggerFactory.getLogger(SiteParamsService.class);
    private static final String FILE = "params.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final StoreCache<String, Map<String, String>> cache = new StoreCache<>(512);

    public SiteParamsService(StorageLayout layout, YamlStore store) {
        this.layout = layout;
        this.store = store;
    }

    /** The site's template parameters (cached; an empty immutable map when unset or unreadable). */
    public Map<String, String> get(String siteId) {
        return cache.get(siteId, this::load);
    }

    /**
     * Persists {@code params} for {@code siteId} and evicts the cache so the next render sees it. An empty
     * map deletes the file (the "no parameters" state), so a cleared editor does not leave an empty document.
     *
     * @throws IOException if the write (or delete) fails
     */
    public void save(String siteId, Map<String, String> params) throws IOException {
        Map<String, String> clean = sanitize(params);
        Path file = file(siteId);
        if (clean.isEmpty()) {
            store.delete(file);
        } else {
            store.write(file, clean);
        }
        cache.invalidate(siteId);
    }

    /** Evict the affected site's entry on any lifecycle change (delete/restore/rename/etc.). */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        cache.invalidate(event.siteId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> load(String siteId) {
        try {
            // Read as a raw map: a hand-edited params.yml may carry scalar numbers/booleans; coerce to
            // strings and reject nesting, exactly as the values are addressed (${params.key}).
            return store.read(file(siteId), Map.class)
                    .map(raw -> sanitize((Map<Object, Object>) raw))
                    .orElseGet(Map::of);
        } catch (IOException | RuntimeException e) { // RuntimeException covers UncheckedIOException/parse errors
            log.warn("could not read {} for site '{}' — serving with no params: {}", FILE, siteId, e.toString());
            return Map.of();
        }
    }

    /**
     * Coerces a raw parameter map to a flat {@code String → String} map: each key trimmed, blank-keyed
     * entries dropped, a {@code null} value coerced to the empty string, and a nested map/list value
     * rejected (it could not be a {@code ${params.key}} value). Insertion order is preserved and the result
     * is immutable and never {@code null}.
     */
    private static Map<String, String> sanitize(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object key = entry.getKey();
            if (key == null) {
                continue;
            }
            String trimmedKey = String.valueOf(key).trim();
            if (trimmedKey.isEmpty()) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map || value instanceof Iterable) {
                throw new IllegalArgumentException(
                        "parameter '" + trimmedKey + "' must be a plain value (no nested maps/lists)");
            }
            clean.put(trimmedKey, value == null ? "" : String.valueOf(value));
        }
        return Collections.unmodifiableMap(clean);
    }

    private Path file(String siteId) {
        return layout.siteDir(siteId).resolve(FILE);
    }
}
