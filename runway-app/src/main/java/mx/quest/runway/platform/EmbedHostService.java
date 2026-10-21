package mx.quest.runway.platform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import mx.quest.runway.platform.PlatformSettings.EmbedHost;
import org.springframework.stereotype.Service;

/**
 * The operator's approved embed hosts ({@link PlatformSettings#embedHosts}): the third-party hosts a site may add to
 * its own public pages' Content Security Policy, each limited to the directives the operator allows it.
 *
 * <h2>Why a list the operator approves</h2>
 * The policy is shared by every site on the server, and each source widens where a site's pages may load content
 * from and send data to. Letting a site type any source would let one compromised site manager account open that
 * site's pages to any host (it cannot add a script host here at all; see {@link #DIRECTIVES} for what that does and
 * does not protect). The operator decides
 * which hosts are acceptable at all, and for what (a video host only in {@code frame-src}); a site only chooses
 * among them ("site adds, platform approves").
 *
 * <h2>Accepted sources</h2>
 * {@code https://host}, {@code https://host:port} or {@code https://*.host} — https only, no path, no keywords
 * ({@code 'unsafe-inline'}, {@code *}, {@code data:} …): a keyword would widen the policy for everything, not
 * for one host.
 */
@Service
public class EmbedHostService {

    /**
     * The directives an approved host may join. Not {@code script-src}, for a practical reason: on a public page a
     * {@code <script src>} in the markup runs only when the edge stamped it with the page's nonce, and the stamp
     * follows the platform-wide allowlist, so a script host added for one site would not run those tags. A host for
     * script tags in the markup is therefore set platform-wide ({@code runway.csp.extra-hosts}, or a plugin's
     * declared hosts).
     *
     * <p>That is <b>not</b> a security boundary, and nothing here should be read as one
     * (ADMINISTRATION §8.4). Under {@code 'strict-dynamic'} a
     * script the site serves from its own origin may insert scripts from any host, so whoever can put JavaScript on
     * a site (its static files, its templates) already decides what runs on its public pages. And a site's scripts
     * share an origin with its own console, so they can act as that site's signed-in console users. What this list
     * does limit is where those scripts may send data ({@code connect-src}, {@code img-src}) and what the pages may
     * frame or load; switching a host on for a site widens exactly that.
     */
    public static final List<String> DIRECTIVES = List.of(
            "frame-src", "img-src", "media-src", "connect-src", "style-src", "font-src");

    /** One DNS label. */
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    /** {@code https://}, an optional {@code *.}, a host of two or more labels, an optional port. */
    private static final Pattern SOURCE =
            Pattern.compile("https://(\\*\\.)?" + LABEL + "(?:\\." + LABEL + ")+(:[0-9]{1,5})?");
    private static final Pattern ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
    private static final int MAX_LABEL = 80;
    private static final int MAX_HOSTS = 100;

    private final PlatformSettingsStore platform;

    public EmbedHostService(PlatformSettingsStore platform) {
        this.platform = platform;
    }

    /** Thrown for an entry the rules refuse; {@code code} names the rule for the console's message. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public Invalid(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The approved hosts, in the order the operator added them. */
    public List<EmbedHost> list() {
        return platform.get().embedHosts();
    }

    /**
     * Approves a host, or replaces the entry with the same id.
     *
     * @throws Invalid naming the first rule the entry breaks
     */
    public synchronized EmbedHost save(String id, String label, String source, List<String> directives) {
        EmbedHost host = validate(id, label, source, directives);
        // Built from the latest list inside one atomic change; a refusal below throws before anything is written.
        platform.update(current -> current.withEmbedHosts(merged(current.embedHosts(), host)));
        return host;
    }

    private static List<EmbedHost> merged(List<EmbedHost> approved, EmbedHost host) {
        List<EmbedHost> next = new ArrayList<>();
        boolean replaced = false;
        for (EmbedHost existing : approved) {
            if (existing.id().equals(host.id())) {
                next.add(host);
                replaced = true;
            } else {
                if (existing.source().equals(host.source())) {
                    throw new Invalid("duplicate", "that source is already approved as '" + existing.id() + "'");
                }
                next.add(existing);
            }
        }
        if (!replaced) {
            if (next.size() >= MAX_HOSTS) {
                throw new Invalid("tooMany", "at most " + MAX_HOSTS + " hosts can be approved");
            }
            next.add(host);
        }
        return next;
    }

    /** Withdraws approval: every site that had chosen the host stops getting it on its next page. */
    public synchronized boolean remove(String id) {
        java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        platform.update(current -> {
            List<EmbedHost> next = new ArrayList<>(current.embedHosts());
            if (!next.removeIf(host -> host.id().equals(id))) {
                return current;
            }
            removed.set(true);
            return current.withEmbedHosts(next);
        });
        return removed.get();
    }

    /** Checks and normalizes one entry. */
    static EmbedHost validate(String id, String label, String source, List<String> directives) {
        String cleanId = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
        if (!ID.matcher(cleanId).matches()) {
            throw new Invalid("badId", "the id uses lowercase letters, digits and dashes, such as youtube");
        }
        String cleanLabel = label == null ? "" : label.strip();
        if (cleanLabel.isEmpty() || cleanLabel.length() > MAX_LABEL) {
            throw new Invalid("badLabel", "give it a name of up to " + MAX_LABEL + " characters");
        }
        String cleanSource = source == null ? "" : source.strip().toLowerCase(Locale.ROOT);
        while (cleanSource.endsWith("/")) {
            cleanSource = cleanSource.substring(0, cleanSource.length() - 1);
        }
        if (!SOURCE.matcher(cleanSource).matches()) {
            throw new Invalid("badSource",
                    "the source must be https:// and a host, such as https://www.youtube-nocookie.com");
        }
        Set<String> chosen = new LinkedHashSet<>();
        for (String directive : directives == null ? List.<String>of() : directives) {
            String d = directive == null ? "" : directive.strip().toLowerCase(Locale.ROOT);
            if (!DIRECTIVES.contains(d)) {
                throw new Invalid("badDirective", "not a directive a host can join: " + directive);
            }
            chosen.add(d);
        }
        if (chosen.isEmpty()) {
            throw new Invalid("noDirective", "choose at least one directive the host may join");
        }
        List<String> ordered = DIRECTIVES.stream().filter(chosen::contains).toList();
        return new EmbedHost(cleanId, cleanLabel, cleanSource, ordered);
    }
}
