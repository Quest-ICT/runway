package mx.quest.runway.page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;

/**
 * File storage for a page's <b>pending editorial work</b>: its review state and, for a published page, the pending
 * revision an editor without {@code APPROVE_PAGES} saved while the site requires review.
 *
 * <h2>Where it lives, and why there</h2>
 * {@code sites/<siteId>/templates/.pending/<slug>/}, holding {@code review.yml} ({@link PageReview}) and — only
 * for a revision — {@code revision.meta.yml}, {@code revision.html} and an optional {@code revision.th.xml}.
 *
 * <ul>
 *   <li><b>Invisible to the live site.</b> Every reader of {@code templates/} lists it non-recursively
 *       ({@link PageStore}, masters, fragments, widget discovery, the preview-scratch sweeper), and the ETag
 *       validator hashes a page's own named files, so a sub-folder is never routed, listed, indexed, put in the
 *       sitemap or folded into a live page's ETag. The leading dot also makes {@code .pending} an id
 *       {@link PathSafety#isSafeId} rejects, so it can never be a page slug or a template name. The file names
 *       are deliberately not {@code <slug>.meta.yml}: a future recursive walker matching that suffix still cannot
 *       mistake a revision for a page.</li>
 *   <li><b>Part of the site.</b> It sits in the site folder, so a backup carries it (a restore brings pending
 *       reviews back with the pages they belong to) and so does a transfer copy — the pending work is site
 *       content, not a secret.</li>
 * </ul>
 *
 * <p>Stateless (no cache) — every read goes to disk, so a cluster node always sees the shared folder's truth.
 * Callers serialize mutations of one page through {@link PageWriteService#withPageLock}; this class only does the
 * file I/O. All paths are confined under the site's templates folder.
 */
public class PendingRevisionStore {

    /** The folder, under a site's {@code templates/}, that holds pending editorial work. */
    public static final String PENDING_DIR = ".pending";
    private static final String REVIEW = "review.yml";
    private static final String META = "revision.meta.yml";
    private static final String HTML = "revision.html";
    private static final String THXML = "revision.th.xml";

    private final StorageLayout layout;
    private final YamlStore store;

    public PendingRevisionStore(StorageLayout layout, YamlStore store) {
        this.layout = layout;
        this.store = store;
    }

    /**
     * A pending revision of a published page: its metadata (slug applied) and sources.
     *
     * @param page  the revised page record (slug applied; its draft flag is whatever was current when saved)
     * @param html  the revised body
     * @param thxml the revised decoupled logic, or {@code null} when the revision has none
     */
    public record Revision(Page page, String html, String thxml) {
    }

    /** The page's review state, or empty when it is not under review. */
    public Optional<PageReview> review(String siteId, String slug) {
        Path dir = dirOrNull(siteId, slug);
        if (dir == null) {
            return Optional.empty();
        }
        try {
            return store.read(dir.resolve(REVIEW), PageReview.class);
        } catch (IOException | RuntimeException e) {
            // An unreadable review.yml must not break the editor or the pages list: treat it as "no review" and
            // let the next submit overwrite it.
            return Optional.empty();
        }
    }

    /** Stores the page's review state (creating the folder). */
    public void writeReview(String siteId, String slug, PageReview review) {
        try {
            store.write(dir(siteId, slug).resolve(REVIEW), review);
        } catch (IOException e) {
            throw new UncheckedIOException("writing review state for " + siteId + "/" + slug, e);
        }
    }

    /** Removes the page's review state (keeping any revision files). */
    public void clearReview(String siteId, String slug) {
        Path dir = dirOrNull(siteId, slug);
        if (dir == null) {
            return;
        }
        try {
            store.delete(dir.resolve(REVIEW));
            deleteIfEmpty(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("clearing review state for " + siteId + "/" + slug, e);
        }
    }

    /** The page's pending revision, or empty when there is none (or its files are incomplete). */
    public Optional<Revision> revision(String siteId, String slug) {
        Path dir = dirOrNull(siteId, slug);
        if (dir == null || !Files.isRegularFile(dir.resolve(META)) || !Files.isRegularFile(dir.resolve(HTML))) {
            return Optional.empty();
        }
        try {
            Optional<Page> page = store.read(dir.resolve(META), Page.class).map(p -> p.withSlug(slug));
            if (page.isEmpty()) {
                return Optional.empty();
            }
            String html = Files.readString(dir.resolve(HTML), StandardCharsets.UTF_8);
            Path logic = dir.resolve(THXML);
            String thxml = Files.isRegularFile(logic) ? Files.readString(logic, StandardCharsets.UTF_8) : null;
            return Optional.of(new Revision(page.get(), html, thxml));
        } catch (IOException e) {
            throw new UncheckedIOException("reading the pending revision of " + siteId + "/" + slug, e);
        }
    }

    /** Whether the page has a pending revision. */
    public boolean hasRevision(String siteId, String slug) {
        Path dir = dirOrNull(siteId, slug);
        return dir != null && Files.isRegularFile(dir.resolve(META));
    }

    /**
     * Writes the page's pending revision: body, then logic (or its removal), then the meta LAST — the same
     * meta-last discipline as a live write, so {@link #revision} never sees meta without its body.
     */
    void writeRevision(String siteId, Page page, String html, String thxml) {
        Path dir = dir(siteId, page.slug());
        try {
            AtomicFiles.write(dir.resolve(HTML), html.getBytes(StandardCharsets.UTF_8));
            Path logic = dir.resolve(THXML);
            if (thxml != null && !thxml.isBlank()) {
                AtomicFiles.write(logic, thxml.getBytes(StandardCharsets.UTF_8));
            } else {
                Files.deleteIfExists(logic);
            }
            store.write(dir.resolve(META), page.withoutSlug());
        } catch (IOException e) {
            throw new UncheckedIOException("writing the pending revision of " + siteId + "/" + page.slug(), e);
        }
    }

    /** Removes everything pending for the page (revision and review state). A no-op when nothing is pending. */
    public void discard(String siteId, String slug) {
        Path dir = dirOrNull(siteId, slug);
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("discarding pending work of " + siteId + "/" + slug, e);
        }
        // .pending itself is left in place even when empty: removing it could race another page's writer that is
        // creating its own sub-folder under a different lock, and an empty folder costs nothing.
    }

    /** Whether anything at all is pending for the page (a review state or a revision). */
    public boolean exists(String siteId, String slug) {
        Path dir = dirOrNull(siteId, slug);
        return dir != null && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
    }

    /** The slugs with pending work in the site, sorted. One directory listing — cheap enough for the pages list. */
    public Set<String> pendingSlugs(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            return Set.of();
        }
        Path root = layout.siteDir(siteId).resolve("templates").resolve(PENDING_DIR);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            Set<String> slugs = new TreeSet<>();
            entries.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString())
                    .filter(PathSafety::isSafeId)
                    .forEach(slugs::add);
            return slugs;
        } catch (IOException e) {
            return Set.of();
        }
    }

    /**
     * A fingerprint of exactly what is under review — the revision's three files when there is a revision, else
     * the page's own three live files. An approver's screen carries it and the approval must present it back, so
     * an approval can only ever publish the content the approver actually looked at: an editor saving again in
     * between changes the fingerprint and the approval is refused.
     *
     * @return the hex SHA-256 (never {@code null}; the digest of nothing when no file exists)
     */
    public String fingerprint(String siteId, String slug) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        Path templates = layout.siteDir(PathSafety.requireSafeId(siteId)).resolve("templates");
        String safe = PathSafety.requireSafeId(slug);
        Path[] files = hasRevision(siteId, slug)
                ? new Path[]{dir(siteId, slug).resolve(META), dir(siteId, slug).resolve(HTML),
                        dir(siteId, slug).resolve(THXML)}
                : new Path[]{PathSafety.resolveConfined(templates, safe + ".meta.yml"),
                        PathSafety.resolveConfined(templates, safe + ".html"),
                        PathSafety.resolveConfined(templates, safe + ".th.xml")};
        try {
            for (Path file : files) {
                byte[] bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
                digest.update((file.getFileName() + ":" + bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("fingerprinting " + siteId + "/" + slug, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // --- internals ---------------------------------------------------------------------------------

    private Path dir(String siteId, String slug) {
        Path dir = dirOrNull(siteId, slug);
        if (dir == null) {
            throw new PageAuthoringException("unsafe-slug", "unsafe id: " + siteId + "/" + slug);
        }
        return dir;
    }

    private Path dirOrNull(String siteId, String slug) {
        if (!PathSafety.isSafeId(siteId) || !PathSafety.isSafeId(slug)) {
            return null;
        }
        Path pending = layout.siteDir(siteId).resolve("templates").resolve(PENDING_DIR);
        return PathSafety.resolveConfined(pending, slug);
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> children = Files.list(dir)) {
                if (children.findAny().isEmpty()) {
                    Files.deleteIfExists(dir);
                }
            }
        }
    }
}
