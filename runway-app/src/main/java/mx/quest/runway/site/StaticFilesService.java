package mx.quest.runway.site;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.UploadNames;
import org.springframework.stereotype.Service;

/**
 * The site's own static files ({@code sites/<id>/static/}, served at {@code /static/…}): the stylesheets, scripts,
 * images, fonts and media its pages reference. The Static files screen manages them through this service, so a
 * site manager can update every resource the site needs without access to the server.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Confined.</b> Every path is relative to {@code static/} and checked here (folder names) and again by
 *       {@link SiteFileStore} (traversal, and no symbolic link inside the site is followed), so a link planted in
 *       {@code static/} cannot carry a delete or an upload into {@code pages/} or past the {@code doc/} rule.</li>
 *   <li><b>{@code doc/} belongs to Documents.</b> That folder holds the managed downloads, with their own
 *       disable/enable state and download headers, so it is shown here but not changed here.</li>
 *   <li><b>Web asset types only.</b> An upload must have one of {@link #ALLOWED_EXTENSIONS}. Pages are not static
 *       files: an {@code .html} upload is refused, so a page always goes through the page editor, its checks and
 *       the editorial workflow.</li>
 *   <li><b>Size.</b> At most {@link #MAX_FILE_BYTES} per file, and the site's files together within its storage
 *       ceiling ({@link SiteStorageCeilings}; unlimited by default).</li>
 *   <li><b>Names.</b> Uploaded names are made URL-safe ({@link UploadNames}); a new folder's name must already be,
 *       and neither may start with a dot. A path to an entry that <i>already exists</i> is checked only for
 *       confinement ({@link #existingPath}): a long uploaded name, or one placed on the server by hand or by a
 *       restore (spaces, accents, {@code @}, a leading dot such as {@code .htaccess}), can always be opened and
 *       deleted. A dot-name is served at {@code /static/} like any other file, so the screen that lists it must be
 *       able to remove it. Only a name with a backslash or a control character cannot be named here.</li>
 * </ul>
 */
@Service
public class StaticFilesService {

    /** The site folder these files live in. */
    public static final String ROOT = "static";
    /** The top-level folder the Documents screen owns. */
    public static final String DOCUMENTS = "doc";
    /** The largest file accepted: 25 MiB, the same as a document. */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    /** How deep folders may nest under {@code static/}. */
    static final int MAX_DEPTH = 8;

    /** The extensions a static file may have: styles, scripts, data, images, fonts, audio, video and PDF. */
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "css", "js", "mjs", "map", "json", "txt", "xml", "webmanifest",
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp",
            "woff", "woff2", "ttf", "otf", "eot",
            "mp3", "ogg", "oga", "wav", "m4a", "mp4", "webm", "ogv", "vtt",
            "pdf");

    private static final Pattern FOLDER_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");

    private final SiteFileStore files;
    private final SiteStorageCeilings ceilings;

    public StaticFilesService(SiteFileStore files, SiteStorageCeilings ceilings) {
        this.files = files;
        this.ceilings = ceilings;
    }

    /** Thrown for a request the rules refuse; {@code code} names the rule for the console's message. */
    public static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * The contents of {@code folder} (relative to {@code static/}; blank is the root), folders first.
     *
     * @throws Refused for a malformed folder path
     */
    public List<SiteFileStore.Entry> list(String siteId, String folder) throws IOException {
        return files.listDirectory(siteId, under(existingPath(folder)));
    }

    /**
     * Whether {@code folder} exists.
     *
     * @throws Refused for a path that is not inside {@code static/}
     */
    public boolean folderExists(String siteId, String folder) {
        String clean = existingPath(folder);
        return clean.isEmpty() ? true : files.isDirectory(siteId, under(clean));
    }

    /**
     * Stores an uploaded file in {@code folder} under its URL-safe name and returns that name.
     *
     * @param replace whether a file of the same name may be overwritten
     * @throws Refused for a Documents path, a disallowed type, a file too large, an unusable name, an existing
     *                 file when {@code replace} is false, or ({@code overCeiling}) a file that would take the site's
     *                 files over their storage ceiling ({@link SiteStorageCeilings})
     */
    public String upload(String siteId, String folder, String filename, byte[] bytes, boolean replace)
            throws IOException {
        String dir = writableFolder(siteId, folder);
        String name;
        try {
            name = UploadNames.safeLeaf(filename);
        } catch (IllegalArgumentException unusable) {
            throw new Refused("badName", "that file name cannot be used: " + filename);
        }
        if (name.startsWith(".")) {
            throw new Refused("badName", "a file name cannot start with a dot: " + name);
        }
        if (!ALLOWED_EXTENSIONS.contains(extension(name))) {
            throw new Refused("badType", "files of this type cannot be uploaded here: " + name);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new Refused("tooLarge", name + " is larger than " + (MAX_FILE_BYTES >> 20) + " MiB");
        }
        String rel = join(dir, name);
        if (files.isDirectory(siteId, under(rel))) {
            throw new Refused("isFolder", "a folder already has that name: " + name);
        }
        if (!replace && files.exists(siteId, under(rel))) {
            throw new Refused("exists", "a file with that name exists: " + name);
        }
        try {
            ceilings.write(siteId, bytes.length, () -> files.size(siteId, under(rel)),
                    () -> files.write(siteId, under(rel), bytes, MAX_FILE_BYTES));
        } catch (SiteStorageCeilings.OverCeiling over) {
            throw new Refused("overCeiling", name + " would take the site's files over their storage limit");
        }
        return name;
    }

    /**
     * Creates the folder {@code name} inside {@code folder}.
     *
     * @throws Refused for a malformed name, a Documents path, too deep a folder, or an existing file of that name
     */
    public void createFolder(String siteId, String folder, String name) throws IOException {
        String dir = writableFolder(siteId, folder);
        String clean = name == null ? "" : name.strip();
        if (!FOLDER_NAME.matcher(clean).matches() || clean.startsWith(".")) {
            throw new Refused("badFolder", "folder names use letters, digits, dot, dash and underscore: " + name);
        }
        String rel = join(dir, clean);
        if (dir.isEmpty() && clean.equalsIgnoreCase(DOCUMENTS)) {
            throw new Refused("documents", "the doc folder is managed on the Documents screen");
        }
        if (rel.split("/").length > MAX_DEPTH) {
            throw new Refused("tooDeep", "folders nest at most " + MAX_DEPTH + " deep");
        }
        if (files.exists(siteId, under(rel))) {
            throw new Refused("exists", "a file already has that name: " + clean);
        }
        files.createDirectory(siteId, under(rel));
    }

    /**
     * Deletes a file, or a folder when it is empty.
     *
     * @return {@code false} when there was nothing to delete or the folder is not empty
     * @throws Refused for a Documents path or one outside {@code static/}
     */
    public boolean delete(String siteId, String path) throws IOException {
        String rel = existingPath(path);
        if (rel.isEmpty()) {
            throw new Refused("badPath", "the static folder itself cannot be deleted");
        }
        requireNotDocuments(rel);
        if (files.isDirectory(siteId, under(rel))) {
            return files.deleteEmptyDirectory(siteId, under(rel));
        }
        return files.delete(siteId, under(rel));
    }

    /** Whether {@code path} (relative to {@code static/}) is inside the Documents folder. */
    public static boolean isDocuments(String path) {
        // Case folded: on a case-insensitive filesystem "DOC/" is the same folder as "doc/", and even where it is not,
        // a second "Doc" folder beside Documents only confuses.
        String rel = path == null ? "" : path.strip().toLowerCase(java.util.Locale.ROOT);
        return rel.equals(DOCUMENTS) || rel.startsWith(DOCUMENTS + "/");
    }

    /** The extension of {@code name}, lowercased; empty when it has none. */
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * {@code folder} checked and written without leading/trailing slashes; blank or {@code null} is the root.
     *
     * @throws Refused when a part is not a plain folder name ({@code ..}, a dot-name, a backslash, …)
     */
    public static String folder(String folder) {
        String f = folder == null ? "" : folder.strip();
        while (f.startsWith("/")) {
            f = f.substring(1);
        }
        while (f.endsWith("/")) {
            f = f.substring(0, f.length() - 1);
        }
        if (f.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : f.split("/")) {
            if (!FOLDER_NAME.matcher(part).matches() || part.startsWith(".")) {
                throw new Refused("badPath", "not a path inside the static folder: " + folder);
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    /**
     * A path naming an entry that already exists under {@code static/}, written without leading/trailing slashes;
     * blank or {@code null} is the root. Unlike {@link #folder}, any name the filesystem can hold is accepted (any
     * length, spaces, leading or trailing spaces, accents, {@code @}, a leading dot such as {@code .htaccess}),
     * because these names were created by an upload or put there on the server; only what could leave
     * {@code static/} is refused: an empty part ({@code a//b}), the parts {@code .} and {@code ..}, a backslash
     * (a separator on Windows) and control characters. {@link SiteFileStore} refuses any symbolic link inside the
     * site.
     *
     * @throws Refused ({@code badPath}) for a path that is not inside {@code static/}
     */
    public static String existingPath(String path) {
        String f = path == null ? "" : path;
        while (f.startsWith("/")) {
            f = f.substring(1);
        }
        while (f.endsWith("/")) {
            f = f.substring(0, f.length() - 1);
        }
        if (f.isEmpty()) {
            return "";
        }
        for (String part : f.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0
                    || part.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new Refused("badPath", "not a path inside the static folder: " + path);
            }
        }
        return f;
    }

    /**
     * The folder an upload or a new folder goes into. A plain folder name ({@link #folder}) may be new; any other
     * name must be an existing folder ({@link #existingPath}), so nothing outside the folder-name rule is created.
     */
    private String writableFolder(String siteId, String folder) {
        String dir;
        try {
            dir = folder(folder);
        } catch (Refused notPlain) {
            dir = existingPath(folder);
            if (!files.isDirectory(siteId, under(dir))) {
                throw notPlain;
            }
        }
        requireNotDocuments(dir);
        return dir;
    }

    private static void requireNotDocuments(String rel) {
        if (isDocuments(rel)) {
            throw new Refused("documents", "the doc folder is managed on the Documents screen");
        }
    }

    private static String join(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    private static String under(String rel) {
        return rel.isEmpty() ? ROOT : ROOT + "/" + rel;
    }
}
