package mx.quest.runway.page;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import mx.quest.runway.storage.FileStamp;

/**
 * The page-validator fold of a site's <b>chrome templates</b>: every template in {@code sites/<id>/templates/} that is
 * not a page — the masters, the fragments the Fragments screen manages ({@code _nav}, {@code _head}, …), any other
 * partial, the error pages — folded by content into one token per site.
 *
 * <h2>Why every non-page template, and not "what this render included"</h2>
 * A master or a page pulls a fragment by a name computed at render time ({@code ~{${master} :: …}}, a
 * {@code #strings.replace}, a conditional), so which templates a render includes is known only by rendering — and the
 * validator runs <em>before</em> the render, so that an unchanged page answers {@code 304} without one. Learning the
 * set from an earlier render does not work either: with the parse cache on, Thymeleaf consults no resolver on a hit,
 * the set is empty after a restart, and a master edit that adds an include would be folded one render late, serving
 * the new body under an ETag that does not describe it. The closed set "every template that is not a page" is known
 * from the folder alone and covers every include a site's own chrome can make. Pages are left out on purpose: another
 * page's edit must not move every page's ETag (and the page being served folds its own files already).
 *
 * <h2>Cost — stamped, not re-read</h2>
 * The token is memoized per site with two {@link FileStamp}s: the folder (taken <em>before</em> it is listed, so a
 * file created, deleted or renamed in it — a console save is an atomic rename — is seen) and the chrome files (taken
 * before they are read). A request whose stamps are current pays one {@code stat} for the folder and one per chrome
 * file; only a change re-lists the folder and re-reads the files. A stamp errs only on the safe side: an edit landing
 * between the stamp and the read leaves the stamp stale, and the next request recomputes. The one undetectable edit is
 * the one {@link FileStamp} documents (an in-place rewrite that forges back its previous size and nanosecond mtime);
 * the parse cache then keeps the old parse too, so ETag and body still agree.
 *
 * <p>What counts as a page: a {@code <name>.html} with a {@code <name>.meta.yml} beside it (the file model the page
 * store uses). Transient preview scratch files ({@code rw-preview-…}) are skipped, as every reader of
 * {@code templates/} skips them. Only {@code .html} and {@code .th.xml} files are folded; sub-folders
 * ({@code .pending/}) are not entered. Thread-safe.
 */
final class ChromeTemplateFold {

    private static final String HTML = ".html";
    private static final String THXML = ".th.xml";
    private static final String META = ".meta.yml";
    /** Above this a chrome file is fingerprinted by size + mtime, never read whole into the heap per change. */
    private static final long MAX_FOLD_BYTES = 10L * 1024 * 1024;

    /** One site's memoized token and the stamps that keep it honest. */
    private record Snapshot(FileStamp folder, FileStamp files, String token) {
        boolean isCurrent() {
            return folder.isCurrent() && files.isCurrent();
        }
    }

    private final Cache<Path, Snapshot> snapshots = Caffeine.newBuilder().maximumSize(4_096).build();

    /**
     * The chrome token of the site whose templates folder is {@code templates}: a hex SHA-256 over the sorted,
     * length-prefixed names and contents of its non-page templates; the empty-folder token when there are none or the
     * folder is absent.
     *
     * @throws UncheckedIOException when the folder exists but cannot be listed or a chrome file cannot be read (the
     *                              page then fails like any other unreadable validator input)
     */
    String token(Path templates) {
        Snapshot cached = snapshots.getIfPresent(templates);
        if (cached != null && cached.isCurrent()) {
            return cached.token();
        }
        Snapshot fresh = take(templates);
        snapshots.put(templates, fresh);
        return fresh.token();
    }

    /** Stamps the folder, lists it, stamps the chrome files, then reads them — in that order (see the class doc). */
    private static Snapshot take(Path templates) {
        FileStamp folder = FileStamp.of(templates);
        List<Path> chrome = Files.isDirectory(templates) ? chromeFiles(templates) : List.of();
        FileStamp files = FileStamp.of(chrome.toArray(Path[]::new));
        MessageDigest digest = sha256();
        for (Path file : chrome) {
            byte[] name = file.getFileName().toString().getBytes(StandardCharsets.UTF_8);
            digest.update((name.length + ":").getBytes(StandardCharsets.US_ASCII));
            digest.update(name);
            byte[] content = content(file);
            digest.update((content.length + ":").getBytes(StandardCharsets.US_ASCII));
            digest.update(content);
        }
        return new Snapshot(folder, files, hex(digest.digest()));
    }

    /** The folder's non-page {@code .html}/{@code .th.xml} regular files, sorted by name (readdir order varies). */
    private static List<Path> chromeFiles(Path templates) {
        List<String> names;
        try (Stream<Path> entries = Files.list(templates)) {
            names = entries.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("listing chrome templates in " + templates, ex);
        }
        Set<String> pages = new HashSet<>();
        for (String name : names) {
            if (name.endsWith(META)) {
                pages.add(name.substring(0, name.length() - META.length()));
            }
        }
        List<Path> chrome = new ArrayList<>();
        for (String name : names) {
            String base = name.endsWith(THXML) ? name.substring(0, name.length() - THXML.length())
                    : name.endsWith(HTML) ? name.substring(0, name.length() - HTML.length()) : null;
            if (base == null || pages.contains(base) || base.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)) {
                continue;
            }
            Path file = templates.resolve(name);
            if (Files.isRegularFile(file)) {
                chrome.add(file);
            }
        }
        return chrome;
    }

    /** The bytes folded for one file: its content, or a size+mtime fingerprint above {@link #MAX_FOLD_BYTES}. */
    private static byte[] content(Path file) {
        try {
            long size = Files.size(file);
            if (size > MAX_FOLD_BYTES) {
                return ("L" + size + ":" + Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8);
            }
            return Files.readAllBytes(file);
        } catch (java.nio.file.NoSuchFileException vanished) {
            return new byte[0]; // deleted since the listing: its stamp already reports the change
        } catch (IOException ex) {
            throw new UncheckedIOException("hashing chrome template " + file, ex);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
