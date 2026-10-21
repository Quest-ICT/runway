package mx.quest.blog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.FileEntry;
import mx.quest.runway.spi.service.PluginSiteFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The blog's per-site file store, over the kernel's {@link PluginSiteFiles} (this plugin's own subtree,
 * {@code sites/<siteId>/plugins/blog/}): {@code entries/<entryId>/entry.yml} (metadata) +
 * {@code entries/<entryId>/body-<lang>.md} (one Markdown body per language). The layout has not changed across releases,
 * so existing entries load unchanged. Everything lives in the site's own folder, so a full backup carries the whole
 * blog with no {@code BackupParticipant}, and the kernel confines every path to the subtree (symbolic links
 * included) — the store cannot reach another plugin's data or another site.
 *
 * <p><b>Metadata/body split on disk</b> is what makes the ContentSource contract trivially honest: {@link #list}
 * reads only {@code entry.yml} files (plus file times for {@code updatedAt}); {@link #readBody} is called only when a
 * body is actually rendered or indexed.
 *
 * <p>Ids and language codes are validated against a strict grammar before ever forming a path — an id is a folder
 * name, a language code a file-name fragment; neither may traverse (the kernel would refuse it anyway).
 *
 * <p><b>Concurrency.</b> Each file write is atomic (the kernel writes a temporary file and renames it), so a public
 * reader — the sitemap, a routing miss — never observes a half-written {@code entry.yml}. Two operators saving the
 * same entry at once: the last save wins.
 *
 * <p><b>Freshness.</b> The store announces nothing; the console calls {@code ContentChanges.changed} after every
 * mutation.
 */
public class BlogStore {

    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,98}[a-z0-9]|[a-z0-9]");
    private static final Pattern SAFE_LANG = Pattern.compile("[a-z]{2,8}");
    /** The entries folder, relative to this plugin's per-site subtree. */
    static final String ENTRIES = "entries";
    static final String METADATA = "entry.yml";

    private static final Logger log = LoggerFactory.getLogger(BlogStore.class);

    /**
     * The YAML codec: <b>lenient</b> on read, so a hand-written {@code entry.yml} that omits {@code published:} or
     * carries a key this version does not know still loads (it defaults instead of failing) — and writes an entry back
     * byte for byte ({@code BlogStoreTest} proves it on the fixture entries). Streams are never auto-closed: the
     * caller owns them.
     */
    private static final ObjectMapper YAML = YAMLMapper.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final PluginSiteFiles files;

    public BlogStore(PluginSiteFiles files) {
        this.files = files;
    }

    /**
     * Every entry of the site (published and not), newest publication date first, with {@code updatedAt} derived
     * from file times. One unreadable entry is skipped with a WARN and never blanks the others.
     *
     * @throws UncheckedIOException if the entries folder itself cannot be listed
     */
    public List<BlogEntry> list(String siteId) {
        List<FileEntry> dirs;
        try {
            dirs = files.list(siteId, ENTRIES);
        } catch (IOException ex) {
            throw new UncheckedIOException("listing blog entries of " + siteId, ex);
        }
        List<BlogEntry> out = new ArrayList<>();
        for (FileEntry dir : dirs) {
            String id = dir.name();
            if (!dir.directory() || !SAFE_ID.matcher(id).matches()) {
                continue;
            }
            // Per-ENTRY containment: one corrupt/hand-mangled entry.yml must never blank the whole blog
            // (the corpus-level backstop upstream would otherwise drop the entire source).
            try {
                read(siteId, id).ifPresent(out::add);
            } catch (RuntimeException ex) {
                log.warn("skipping unreadable blog entry {}/{}: {}", siteId, id, ex.toString());
            }
        }
        out.sort((a, b) -> {
            // Newest first for the console: by publishedOn (desc, nulls last), then id for stability.
            String pa = a.publishedOn() == null ? "" : a.publishedOn();
            String pb = b.publishedOn() == null ? "" : b.publishedOn();
            int byDate = pb.compareTo(pa);
            return byDate != 0 ? byDate : a.id().compareTo(b.id());
        });
        return out;
    }

    /**
     * One entry by id, or empty (also empty for an unsafe id — never a path probe — and for a malformed
     * {@code entry.yml}, which is logged).
     *
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    public Optional<BlogEntry> read(String siteId, String entryId) {
        if (!SAFE_ID.matcher(entryId).matches()) {
            return Optional.empty();
        }
        String path = metadataPath(entryId);
        Optional<String> text;
        try {
            text = files.readText(siteId, path);
        } catch (IOException ex) {
            throw new UncheckedIOException("reading blog entry " + siteId + "/" + entryId, ex);
        }
        if (text.isEmpty()) {
            return Optional.empty();
        }
        BlogEntry parsed;
        try {
            parsed = YAML.readValue(text.get(), BlogEntry.class);
        } catch (RuntimeException parseFailure) {
            // A malformed file is THIS entry's problem, never the corpus's: absent + a WARN naming the file.
            log.warn("unparseable blog entry {}/{} — treated as absent: {}", siteId, path, parseFailure.toString());
            return Optional.empty();
        }
        if (parsed == null) {
            return Optional.empty();
        }
        return Optional.of(parsed.withId(entryId).withUpdatedAt(newestModification(siteId, entryId)));
    }

    /**
     * Creates or replaces an entry's metadata, and any bodies given ({@code lang → markdown}; a {@code null} body
     * map leaves bodies untouched, a blank body deletes that language's file). The caller announces the change
     * afterwards — the store never does.
     *
     * @throws IllegalArgumentException for an unsafe id or language code (nothing is written), or a body over the
     *                                  kernel's write cap
     * @throws IOException              when a write fails (for example, the site folder does not exist)
     */
    public void save(String siteId, BlogEntry entry, Map<String, String> bodies) throws IOException {
        requireSafeId(entry.id());
        if (bodies != null) {
            bodies.keySet().forEach(BlogStore::requireSafeLang); // validate ALL before writing ANY
        }
        files.writeText(siteId, metadataPath(entry.id()), YAML.writeValueAsString(entry.forStorage()));
        if (bodies != null) {
            for (Map.Entry<String, String> body : bodies.entrySet()) {
                String path = bodyPath(entry.id(), body.getKey());
                if (body.getValue() == null || body.getValue().isBlank()) {
                    files.delete(siteId, path);
                } else {
                    files.writeText(siteId, path, body.getValue());
                }
            }
        }
    }

    /**
     * Deletes an entry (its folder and every file in it; symbolic links are removed, never followed). Unknown and
     * unsafe ids are a no-op.
     */
    public void delete(String siteId, String entryId) throws IOException {
        if (!SAFE_ID.matcher(entryId).matches()) {
            return;
        }
        files.deleteTree(siteId, ENTRIES + "/" + entryId);
    }

    /**
     * The entry's Markdown body for {@code lang}, or {@code ""} when that language has none (or the id or language
     * is unsafe).
     *
     * @throws UncheckedIOException if the body exists but cannot be read (including one over the kernel's cap)
     */
    public String readBody(String siteId, String entryId, String lang) {
        if (!SAFE_ID.matcher(entryId).matches() || !SAFE_LANG.matcher(lang).matches()) {
            return "";
        }
        try {
            return files.readText(siteId, bodyPath(entryId, lang)).orElse("");
        } catch (IOException ex) {
            throw new UncheckedIOException("reading blog body " + siteId + "/" + entryId + "/" + lang, ex);
        }
    }

    /** The languages that have a body file for {@code entryId}, sorted. */
    public List<String> bodyLangs(String siteId, String entryId) {
        if (!SAFE_ID.matcher(entryId).matches()) {
            return List.of();
        }
        List<String> langs = new ArrayList<>();
        for (FileEntry file : entryFiles(siteId, entryId)) {
            String name = file.name();
            if (!file.directory() && name.startsWith("body-") && name.endsWith(".md")) {
                langs.add(name.substring("body-".length(), name.length() - ".md".length()));
            }
        }
        langs.sort(String::compareTo);
        return langs;
    }

    static void requireSafeId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid blog entry id: '" + id + "' (lowercase letters, "
                    + "digits and hyphens; no ':' — the composite blog:<id> identity must stay unambiguous)");
        }
    }

    private static String requireSafeLang(String lang) {
        if (lang == null || !SAFE_LANG.matcher(lang).matches()) {
            throw new IllegalArgumentException("invalid language code: '" + lang + "'");
        }
        return lang;
    }

    /**
     * The newest modification time of the entry's files — {@code updatedAt}, which drives the sitemap
     * {@code <lastmod>} and the render {@code ETag} once the source re-lists (after an announced change).
     * {@code null} when no time is known.
     */
    private Instant newestModification(String siteId, String entryId) {
        Instant newest = null;
        for (FileEntry file : entryFiles(siteId, entryId)) {
            Instant at = file.lastModified();
            if (!file.directory() && !Instant.EPOCH.equals(at) && (newest == null || at.isAfter(newest))) {
                newest = at;
            }
        }
        return newest;
    }

    private List<FileEntry> entryFiles(String siteId, String entryId) {
        try {
            return files.list(siteId, ENTRIES + "/" + entryId);
        } catch (IOException ex) {
            // An unreadable folder contributes no file times and no body languages; the entry still lists.
            log.debug("listing blog entry {}/{} failed: {}", siteId, entryId, ex.toString());
            return List.of();
        }
    }

    private static String metadataPath(String entryId) {
        return ENTRIES + "/" + entryId + "/" + METADATA;
    }

    private static String bodyPath(String entryId, String lang) {
        return ENTRIES + "/" + entryId + "/body-" + requireSafeLang(lang) + ".md";
    }
}
