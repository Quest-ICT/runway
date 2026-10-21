package mx.quest.runway.i18n;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.stereotype.Service;

/**
 * Reads and writes a site's own message bundles — {@code sites/<id>/i18n/messages[_<locale>].properties} — for
 * the tenant console's <b>Messages</b> editor. This is the write side of the same files
 * {@link SiteAwareMessageSource} reads on every public render; the two agree on layout and encoding so a saved
 * value resolves at once (the controller evicts the read cache after a save).
 *
 * <h2>File layout</h2>
 * The site's <b>default locale</b> lives in the base {@code messages.properties}; every other locale in a
 * suffixed {@code messages_<tag>.properties}. This is exactly {@link org.springframework.context.support.ReloadableResourceBundleMessageSource}'s
 * own fallback model, so what the editor writes is what the reader resolves. All files are UTF-8 — matching the
 * read side's {@code defaultEncoding} — never ISO-8859-1 with {@code \\uXXXX} escapes.
 *
 * <h2>Editable model</h2>
 * {@link #load} returns a <b>key → (locale → value)</b> grid: every key present in any of the site's locale
 * bundles, each with its per-locale value (absent ⇒ empty string). Keys are sorted so the grid, the written
 * files, and any backup diff stay stable and reviewable. A locale for which a key has no entry is a genuine gap
 * (Runway has <b>no app-level fallback</b>: an unresolved key surfaces as {@code ??key??}), which the editor
 * shows as a blank input the manager can fill.
 *
 * <h2>Blank means "no override"</h2>
 * {@link #save} treats a <b>blank</b> value as "this locale has no value for this key" and <b>omits</b> the key
 * from that locale's file — keeping bundles free of empty {@code key=} lines. An
 * empty-but-present entry is therefore not representable (nor useful — it would resolve to the empty string, not
 * a fallback); clearing a field is the way to drop a key from one locale, and a key blank in <em>every</em>
 * locale disappears entirely.
 *
 * <h2>Escaping and round-trip</h2>
 * Reading goes through {@link Properties#load(Reader)} so every standard escape (unicode {@code \\uXXXX},
 * escaped {@code =}/{@code :}, line continuations) is decoded correctly. Writing is a hand-rolled deterministic
 * serializer — sorted keys, UTF-8, <b>no</b> {@code Properties.store} timestamp comment (which would churn the
 * file needlessly) — that escapes keys and values so any input (a {@code =} or {@code :} in a value, a newline,
 * a unicode character, a leading space) round-trips back through the reader intact and can never corrupt the
 * file (e.g. inject a second key by smuggling a raw newline).
 *
 * <h2>Isolation</h2>
 * Every path is derived from {@link StorageLayout#siteDir(String)} (traversal-safe, tenant-scoped) plus a fixed
 * {@code i18n/messages…} name, so a call can only ever touch the named site's own {@code i18n/} folder — never
 * another site's, never application config. The controller passes only the <b>bound</b> tenancy id, never a
 * client-supplied one.
 *
 * <h2>Concurrency</h2>
 * {@link #merge} is a read-modify-write (unlike the full-replace {@link #save}, which never read first), so two
 * concurrent writers to the same site could lose an update. All three mutators — {@link #save}, {@link #merge}
 * and {@link #migrateDefaultLocale} — therefore serialize on a <b>per-site lock</b> (a striped map keyed by site
 * id), so writes to one site never block another. The guarantee is <em>intra-JVM</em> only: in a clustered
 * deployment concurrent writes from different nodes are last-writer-wins at file granularity — an accepted,
 * documented limitation while clustering defaults OFF (a distributed lock is the follow-up).
 */
@Service
public class SiteMessagesStore {

    private static final String I18N_DIR = "i18n";
    private static final String BASE_FILE = "messages.properties";

    private final StorageLayout layout;

    /** Per-site write lock (bounded by the number of sites ever written; entries are cheap and long-lived). */
    private final ConcurrentMap<String, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    public SiteMessagesStore(StorageLayout layout) {
        this.layout = layout;
    }

    /** The write lock for one site, created on first use — serializes {@link #save}/{@link #merge}/{@link #migrateDefaultLocale}. */
    private ReentrantLock lockFor(String siteId) {
        return writeLocks.computeIfAbsent(siteId, k -> new ReentrantLock());
    }

    /**
     * The site's editable message grid: every key defined in any of the {@code locales} bundles, mapped to a
     * per-locale value (empty string where that locale has no entry). Keys are sorted; the inner map is ordered
     * by the given {@code locales} so the editor renders columns in the site's own language order.
     *
     * @param siteId  the bound tenancy id (traversal-safe; never client-supplied)
     * @param locales the site's available locales, default first (the {@link mx.quest.runway.site.Site} convention)
     * @return an ordered {@code key → (localeTag → value)} view
     */
    public Map<String, Map<String, String>> load(String siteId, List<Locale> locales) {
        // Read each locale's file once into a per-locale key→value map, and gather the union of all keys.
        Map<String, Map<String, String>> perLocale = new TreeMap<>();
        TreeSet<String> allKeys = new TreeSet<>();
        for (Locale locale : locales) {
            Map<String, String> values = read(file(siteId, locale, isDefault(locale, locales)));
            perLocale.put(locale.toLanguageTag(), values);
            allKeys.addAll(values.keySet());
        }
        // Pivot to key → (localeTag → value), keeping locale column order and filling gaps with "".
        Map<String, Map<String, String>> grid = new TreeMap<>();
        for (String key : allKeys) {
            Map<String, String> row = new java.util.LinkedHashMap<>();
            for (Locale locale : locales) {
                String tag = locale.toLanguageTag();
                row.put(tag, perLocale.get(tag).getOrDefault(key, ""));
            }
            grid.put(key, row);
        }
        return grid;
    }

    /**
     * Persists the edited grid. For each of the site's {@code locales}, the corresponding file is rewritten with
     * exactly the non-blank values for that locale (blank ⇒ key omitted ⇒ "no override"); an empty result deletes
     * the file so a fully-cleared locale leaves no stray empty bundle. Only this site's {@code i18n/} files are
     * touched.
     *
     * @param siteId  the bound tenancy id (traversal-safe; never client-supplied)
     * @param locales the site's available locales, default first
     * @param grid    the submitted {@code key → (localeTag → value)} values (values may be blank/null)
     * @throws IOException if a file write or delete fails
     */
    public void save(String siteId, List<Locale> locales, Map<String, Map<String, String>> grid) throws IOException {
        ReentrantLock lock = lockFor(siteId);
        lock.lock();
        try {
            for (Locale locale : locales) {
                String tag = locale.toLanguageTag();
                Map<String, String> forLocale = new TreeMap<>();
                grid.forEach((key, row) -> {
                    String cleanKey = key == null ? null : key.strip();
                    if (cleanKey == null || cleanKey.isEmpty() || row == null) {
                        return;
                    }
                    String value = row.get(tag);
                    if (value != null && !value.isBlank()) {
                        forLocale.put(cleanKey, value); // preserve the value verbatim (leading/trailing kept via escaping)
                    }
                });
                Path file = file(siteId, locale, isDefault(locale, locales));
                if (forLocale.isEmpty()) {
                    Files.deleteIfExists(file);
                } else {
                    write(file, forLocale);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a <b>partial</b> edit to the site's bundles — the incremental-save counterpart to the full-replace
     * {@link #save}. It upserts the given cells and removes the given keys, touching <b>only the affected files</b>
     * and leaving every other key intact, so a one-cell change is a one-line file diff (not a whole-bundle
     * rewrite). Used by the Messages editor's "save only what changed" path.
     *
     * <p>Semantics mirror the editor's model and {@link #save}'s "blank ⇒ no override" rule:
     * <ul>
     *   <li>a cell with a <b>non-blank</b> value ⇒ upsert {@code key=value} in that locale's file;</li>
     *   <li>a cell with a <b>blank</b> value ⇒ remove {@code key} from <em>that locale only</em> (clearing a field
     *       drops the key from that one locale, exactly as a full save would);</li>
     *   <li>a key in {@code removedKeys} ⇒ removed from <b>every</b> locale (a full delete). Removal wins over any
     *       upsert of the same key in the same call.</li>
     * </ul>
     * A locale file is rewritten (or deleted, when it becomes empty) only if its content actually changed; an
     * unaddressed or unchanged locale file is left untouched. Serialized per site with {@link #save} and
     * {@link #migrateDefaultLocale} (see the class's Concurrency note), so a concurrent full save cannot interleave
     * a lost update.
     *
     * @param siteId      the bound tenancy id (traversal-safe; never client-supplied)
     * @param locales     the site's available locales, default first (maps the default to the base file)
     * @param upserts     locale-tag → (key → value) edits; a blank value removes that key from that locale
     * @param removedKeys keys to remove from every locale (may be empty)
     * @throws IOException if a file read/write/delete fails
     */
    public void merge(String siteId, List<Locale> locales,
                      Map<String, Map<String, String>> upserts, Set<String> removedKeys) throws IOException {
        Map<String, Map<String, String>> ups = upserts == null ? Map.of() : upserts;
        Set<String> removed = removedKeys == null ? Set.of() : removedKeys;
        ReentrantLock lock = lockFor(siteId);
        lock.lock();
        try {
            for (Locale locale : locales) {
                String tag = locale.toLanguageTag();
                Map<String, String> forLocale = ups.getOrDefault(tag, Map.of());
                if (forLocale.isEmpty() && removed.isEmpty()) {
                    continue; // nothing addressed to this locale
                }
                Path file = file(siteId, locale, isDefault(locale, locales));
                Map<String, String> next = new TreeMap<>(read(file));
                boolean changed = false;

                // Upserts: non-blank ⇒ put; blank ⇒ remove that key from THIS locale only.
                for (Map.Entry<String, String> e : forLocale.entrySet()) {
                    String key = e.getKey() == null ? null : e.getKey().strip();
                    if (key == null || key.isEmpty()) {
                        continue;
                    }
                    String value = e.getValue();
                    if (value == null || value.isBlank()) {
                        changed |= next.remove(key) != null;
                    } else {
                        changed |= !value.equals(next.put(key, value)); // put returns the prior value (null if new)
                    }
                }
                // Removals: drop the key from every locale (removal wins over an upsert of the same key above).
                for (String rk : removed) {
                    String key = rk == null ? null : rk.strip();
                    if (key != null && !key.isEmpty()) {
                        changed |= next.remove(key) != null;
                    }
                }
                if (changed) {
                    writeOrDelete(file, next);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Migrates the site's bundle files when its <b>default locale changes</b> from {@code oldDefault} to
     * {@code newDefault}, restoring the invariant this store relies on — <em>the base {@code messages.properties}
     * always holds the CURRENT default locale's content</em>. Nothing else maintains it: {@link #load}/{@link
     * #save} map the suffix-less base file to {@code locales.get(0)} (always the current default), so without
     * this the base file — still holding the OLD default's text — would silently be re-labelled as the new
     * default's column, and the old default's column would read a {@code messages_<tag>} file that was never
     * written (the reported en→es bug: English shown as Spanish, English blank).
     *
     * <p>It re-homes two files (a no-op when the default is unchanged or the files are absent):
     * <ol>
     *   <li>the old default's content (in the base file) → its own {@code messages_<oldTag>.properties} — merged
     *       into any pre-existing one, base values winning, so no prior override is lost;</li>
     *   <li>the new default's content ({@code messages_<newTag>.properties}, if any) → the base file, and the now
     *       redundant suffixed file is removed.</li>
     * </ol>
     * If the new default had no bundle yet, the base file is simply cleared (its old content preserved as the
     * old default's suffixed file) — the honest result of switching the default to an untranslated locale. The
     * caller must evict the read cache afterward (a site change now does).
     *
     * @param siteId      the bound tenancy id (traversal-safe; never client-supplied)
     * @param oldDefault  the locale that was the default before this change (may be {@code null})
     * @param newDefault  the locale that is the default after this change (may be {@code null})
     * @throws IOException if a file read/write/delete fails (the caller must then NOT treat the change as applied)
     */
    public void migrateDefaultLocale(String siteId, Locale oldDefault, Locale newDefault) throws IOException {
        if (oldDefault == null || newDefault == null || oldDefault.equals(newDefault)) {
            return;
        }
        ReentrantLock lock = lockFor(siteId);
        lock.lock();
        try {
            Path i18n = layout.siteDir(siteId).resolve(I18N_DIR);
            Path base = i18n.resolve(BASE_FILE);
            Path oldSuffixed = i18n.resolve("messages_" + oldDefault.toLanguageTag() + ".properties");
            Path newSuffixed = i18n.resolve("messages_" + newDefault.toLanguageTag() + ".properties");

            // 1) The old default (in base) becomes its own suffixed override — merged into any pre-existing file
            //    (base wins, as the authoritative old-default content). The base file is NOT deleted here: step 2
            //    ATOMICALLY overwrites it, so the default's bundle is never momentarily missing (a mid-way IO
            //    failure leaves the ORIGINAL base intact, since the record is only saved on success).
            if (Files.isRegularFile(base)) {
                Map<String, String> merged = new TreeMap<>();
                if (Files.isRegularFile(oldSuffixed)) {
                    merged.putAll(read(oldSuffixed));
                }
                merged.putAll(read(base));
                writeOrDelete(oldSuffixed, merged);
            }
            // 2) The new default's content takes the base slot (atomic overwrite); its now-redundant suffixed file
            //    is removed. If the new default had no bundle yet, the base is cleared (its old content is safe in
            //    step 1's suffixed file) — the honest result of switching to an untranslated default.
            if (Files.isRegularFile(newSuffixed)) {
                writeOrDelete(base, read(newSuffixed));
                Files.deleteIfExists(newSuffixed);
            } else {
                Files.deleteIfExists(base);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Writes the values, or deletes the file when there are none — never leaves a stray 0-byte bundle. */
    private static void writeOrDelete(Path file, Map<String, String> values) throws IOException {
        if (values.isEmpty()) {
            Files.deleteIfExists(file);
        } else {
            write(file, values);
        }
    }

    // --- file layout -------------------------------------------------------------------------------------------

    private boolean isDefault(Locale locale, List<Locale> locales) {
        return !locales.isEmpty() && locales.get(0).equals(locale);
    }

    /** The bundle file for one (site, locale): the base file for the default locale, else a {@code _<tag>} file. */
    private Path file(String siteId, Locale locale, boolean isDefault) {
        String name = isDefault ? BASE_FILE : "messages_" + locale.toLanguageTag() + ".properties";
        // siteDir validates the id (traversal-safe); the fixed i18n/<name> can never escape it.
        return layout.siteDir(siteId).resolve(I18N_DIR).resolve(name);
    }

    // --- properties I/O ----------------------------------------------------------------------------------------

    private static Map<String, String> read(Path file) {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader); // decodes standard escapes (\\uXXXX, \\=, \\:, continuations)
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException("Failed reading " + file, ex);
        }
        for (String key : props.stringPropertyNames()) {
            out.put(key, props.getProperty(key));
        }
        return out;
    }

    private static void write(Path file, Map<String, String> values) throws IOException {
        StringBuilder body = new StringBuilder();
        new TreeMap<>(values).forEach((key, value) ->
                body.append(escapeKey(key)).append('=').append(escapeValue(value)).append('\n'));
        AtomicFiles.write(file, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Escapes a property key: the separators and comment/leading-significant characters are backslash-escaped and
     * every whitespace character is written as its {@code \\n}/{@code \\r}/{@code \\t}/{@code \\ } escape, so the
     * whole key is one line and parses back byte-for-byte through {@link Properties}.
     */
    private static String escapeKey(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            switch (c) {
                case '=', ':', '#', '!', ' ', '\\' -> sb.append('\\').append(c);
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapes a property value: backslash and all newlines/tabs are escaped (so a multi-line value stays one
     * physical line and can never inject a spurious key), and a <b>leading</b> space is escaped (a
     * {@link Properties} value's leading whitespace is otherwise stripped on read). A {@code =} or {@code :} needs
     * no escaping past the key, and trailing spaces are preserved as-is.
     */
    private static String escapeValue(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ' -> sb.append(i == 0 ? "\\ " : " ");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
