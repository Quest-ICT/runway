package mx.quest.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mx.quest.runway.spi.service.PluginSiteFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The demo's per-site "sticky notes" — data the plugin <b>generates for a site</b>, kept in the plugin's own per-site
 * folder through the kernel's {@link PluginSiteFiles}: {@value #FILE} under {@code sites/<id>/plugins/runway-demo/}.
 *
 * <p><b>When to use {@code PluginSiteFiles}.</b> For anything your plugin stores per site. The folder lives inside the
 * site's folder, so a full backup carries it, a deleted site takes it along, and nobody else's code can reach it — no
 * {@code BackupParticipant} is needed (1.x kept the notes outside the site folder and needed one; 2.0.0 does not).
 * The service is bound to <i>this</i> plugin by the jar the injecting class came from, so no method takes a plugin id.
 *
 * <p><b>Personal data.</b> The notes hold what people typed — an operator's notes from the console and a visitor's
 * message from the public feedback form — so {@code plugin.yml} declares {@value #FILE} under
 * {@code siteData.personal}: a transfer copy of the site leaves it out. Rule of thumb: any file holding visitor or
 * user input is personal.
 *
 * <p><b>Concurrency.</b> Each kernel write is atomic (readers never see half a file), but an append is a
 * read-modify-write, which the service leaves to the plugin: it is serialized here per site. The file is bounded to
 * the newest {@value #MAX_NOTES} notes, far below the kernel's per-write cap.
 *
 * <p><b>A {@code GET} never writes.</b> A site with no notes yet shows a welcome note, but the welcome is not stored
 * until something else is: the first {@link #append} (a console note, a visitor's message, the settings audit note)
 * writes the welcome and the new note together, so the note an operator saw stays in the list. Until then the site
 * has no {@value #FILE} at all. Reads ({@link #list}, {@link #count}, {@link #recent}) never create a file — opening
 * a console screen or the settings page must not change a site.
 *
 * <p><b>Failure.</b> A read failure during {@link #append} aborts the append (rewriting the file from nothing would
 * lose every note); the console reads ({@link #list}, {@link #count}) degrade to empty with a warning, so an
 * unreadable file never takes a console screen down. The YAML goes through the plugin's own Jackson (a platform
 * library), lenient about keys it does not know, so a later version can add fields without breaking this one.
 */
public class DemoNotesStore {

    /** The notes file, relative to the plugin's per-site folder; declared personal in {@code plugin.yml}. */
    static final String FILE = "notes.yml";
    /** The most notes a site keeps (the oldest are dropped). */
    static final int MAX_NOTES = 200;
    /** The longest note text kept, in characters. */
    static final int MAX_TEXT = 1000;
    /**
     * The welcome note's fixed id (never one {@code newId} makes), so the welcome a site shows is the note its first
     * write keeps.
     */
    static final String WELCOME_ID = "welcome";

    private static final Logger log = LoggerFactory.getLogger(DemoNotesStore.class);

    private final PluginSiteFiles files;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public DemoNotesStore(PluginSiteFiles files) {
        this.files = files;
    }

    private Object lockFor(String siteId) {
        return locks.computeIfAbsent(siteId, k -> new Object());
    }

    /**
     * The site's notes, oldest first. A site with no notes yet reads as the one {@linkplain #welcome() welcome note},
     * which is <b>not written</b> (see the class comment). Empty, with a warning, when the file cannot be read.
     */
    public List<Note> list(String siteId) {
        synchronized (lockFor(siteId)) {
            NotesFile file;
            try {
                file = read(siteId);
            } catch (IOException | JacksonException e) {
                log.warn("runway-demo notes of site {} could not be read: {}", siteId, e.toString());
                return List.of();
            }
            return file.notes().isEmpty() ? List.of(welcome()) : file.notes();
        }
    }

    /**
     * The note a site with no notes shows. Its id is fixed and it has no time ({@code createdAt} is empty) while it is
     * only shown; the first {@link #append} stores it with that write's time.
     */
    static Note welcome() {
        return new Note(WELCOME_ID, "Welcome — this note is data the runway-demo plugin keeps for this site in its "
                + "own folder; it travels in full backups and is left out of transfer copies.", "",
                Note.ORIGIN_SYSTEM);
    }

    /**
     * Appends a note (its text stripped and capped at {@value #MAX_TEXT} characters) and returns the new count. The
     * first append to a site with no notes also stores the {@linkplain #welcome() welcome note}, before the new one.
     *
     * @param origin who wrote it: {@link Note#ORIGIN_CONSOLE}, {@link Note#ORIGIN_VISITOR} or {@link Note#ORIGIN_SYSTEM}
     * @throws UncheckedIOException when the file cannot be read or written (an unreadable file is left untouched)
     */
    public int append(String siteId, String text, String origin) {
        String clean = text == null ? "" : text.strip();
        if (clean.length() > MAX_TEXT) {
            clean = clean.substring(0, MAX_TEXT);
        }
        synchronized (lockFor(siteId)) {
            try {
                List<Note> notes = new ArrayList<>(read(siteId).notes());
                String now = Instant.now().toString();
                if (notes.isEmpty()) {
                    // The first write keeps the welcome the site was showing, stamped with this write's time.
                    Note shown = welcome();
                    notes.add(new Note(shown.id(), shown.text(), now, shown.origin()));
                }
                notes.add(new Note(newId(), clean, now, origin));
                if (notes.size() > MAX_NOTES) {
                    notes = new ArrayList<>(notes.subList(notes.size() - MAX_NOTES, notes.size()));
                }
                write(siteId, new NotesFile(notes));
                return notes.size();
            } catch (IOException e) {
                throw new UncheckedIOException("storing a runway-demo note for site " + siteId, e);
            } catch (JacksonException unparsable) {
                // Jackson 3 exceptions are unchecked; an unparsable file is as unreadable as an I/O failure.
                throw new UncheckedIOException("storing a runway-demo note for site " + siteId,
                        new IOException(unparsable));
            }
        }
    }

    /** The number of notes the site shows (1, the unsaved welcome, for a site with none yet; as {@link #list}). */
    public int count(String siteId) {
        return list(siteId).size();
    }

    /** The newest {@code limit} notes, newest first. */
    public List<Note> recent(String siteId, int limit) {
        List<Note> all = list(siteId);
        return all.subList(Math.max(0, all.size() - limit), all.size()).reversed();
    }

    private NotesFile read(String siteId) throws IOException {
        Optional<String> text = files.readText(siteId, FILE);
        if (text.isEmpty() || text.get().isBlank()) {
            return NotesFile.empty();
        }
        NotesFile parsed = yaml.readValue(text.get(), NotesFile.class);
        return parsed == null ? NotesFile.empty() : parsed;
    }

    private void write(String siteId, NotesFile file) throws IOException {
        files.writeText(siteId, FILE, yaml.writeValueAsString(file));
    }

    private static String newId() {
        return "n-" + Long.toString(System.nanoTime(), 36);
    }
}
