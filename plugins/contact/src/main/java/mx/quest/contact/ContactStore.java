package mx.quest.contact;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mx.quest.runway.spi.service.PluginSiteFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists contact-form submissions as tenant-owned data in this plugin's per-site folder, through the kernel's
 * {@link PluginSiteFiles}: {@value #FILE} under {@code sites/<id>/plugins/contact/}; an existing file is read and
 * appended in place. The folder is <b>outside</b> the served {@code static/} tree (never publicly downloadable) and
 * inside the site folder, so full backups carry it. The file holds what visitors typed, so {@code plugin.yml}
 * declares it <b>personal data</b> ({@code siteData.personal}) and a transfer copy of the site leaves it out.
 *
 * <p>One tab-separated line per submission ({@code epochMillis \t name \t email \t message}); fields are
 * sanitized to strip tabs/newlines so the format stays unambiguous, and each field is capped at
 * {@value #MAX_FIELD} characters. The file is bounded to the most recent {@link #MAX_SUBMISSIONS}, so it stays
 * a few MiB — well under the kernel's per-write cap.
 *
 * <p><b>Concurrency.</b> Each kernel write is atomic, but appending is a read-modify-write, which the service
 * leaves to the plugin: it is serialized here per site (contact volume is low). <b>Failure.</b> A read failure
 * during {@link #add} aborts the append rather than rewriting the file from nothing (which would lose every
 * stored submission); the console reads ({@link #count}, {@link #recent}) degrade to empty with a warning so an
 * unreadable file never takes the settings screen down.
 */
public class ContactStore {

    static final int MAX_SUBMISSIONS = 500;
    static final String FILE = "submissions.tsv";
    static final int MAX_FIELD = 4000;
    private static final Logger log = LoggerFactory.getLogger(ContactStore.class);

    private final PluginSiteFiles files;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public ContactStore(PluginSiteFiles files) {
        this.files = files;
    }

    /**
     * Appends a submission (stamped {@code epochMillis}), keeping only the most recent {@link #MAX_SUBMISSIONS}.
     *
     * @throws UncheckedIOException when the file cannot be read or written (nothing is lost: an unreadable file is
     *                              left untouched)
     */
    public void add(String siteId, String name, String email, String message, long epochMillis) {
        String line = epochMillis + "\t" + sanitize(name) + "\t" + sanitize(email) + "\t" + sanitize(message);
        synchronized (locks.computeIfAbsent(siteId, k -> new Object())) {
            try {
                List<String> lines = new ArrayList<>(rawLines(siteId));
                lines.add(line);
                if (lines.size() > MAX_SUBMISSIONS) {
                    lines = lines.subList(lines.size() - MAX_SUBMISSIONS, lines.size());
                }
                files.writeText(siteId, FILE, String.join("\n", lines) + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException("storing a contact submission for site " + siteId, e);
            }
        }
    }

    /** The most recent submissions, newest first, at most {@code limit}; empty when the file cannot be read. */
    public List<ContactSubmission> recent(String siteId, int limit) {
        List<String> lines = new ArrayList<>(readableLines(siteId));
        Collections.reverse(lines);
        List<ContactSubmission> out = new ArrayList<>();
        for (String line : lines) {
            if (out.size() >= limit) {
                break;
            }
            ContactSubmission s = parse(line);
            if (s != null) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /** The total number of stored submissions; 0 when the file cannot be read. */
    public int count(String siteId) {
        return readableLines(siteId).size();
    }

    private List<String> readableLines(String siteId) {
        try {
            return rawLines(siteId);
        } catch (IOException e) {
            log.warn("contact submissions of site {} could not be read: {}", siteId, e.toString());
            return List.of();
        }
    }

    private List<String> rawLines(String siteId) throws IOException {
        return files.readText(siteId, FILE)
                .map(text -> List.of(text.split("\n")).stream().filter(l -> !l.isBlank()).toList())
                .orElse(List.of());
    }

    private static ContactSubmission parse(String line) {
        String[] p = line.split("\t", 4);
        if (p.length < 4) {
            return null;
        }
        try {
            return new ContactSubmission(Long.parseLong(p[0].trim()), p[1], p[2], p[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String s = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return s.length() > MAX_FIELD ? s.substring(0, MAX_FIELD) : s;
    }
}
