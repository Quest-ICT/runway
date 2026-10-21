package mx.quest.runway.site;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one rule for the hosts a site answers on, applied by every path that gives a site its hosts: create, demo
 * install, Edit hosts, create-from-backup and restore.
 *
 * <h2>Why one place</h2>
 * Until 1.0.0 only Edit hosts checked them. Create, demo install and create-from-backup saved whatever they were
 * given, so two sites could claim the same host and the resolver silently answered with whichever it indexed
 * first. A host must belong to at most one site, and every path must say so the same way.
 */
public final class SiteHosts {

    private static final Logger LOG = LoggerFactory.getLogger(SiteHosts.class);

    /** Upper bound on a DNS hostname (RFC 1035). */
    private static final int MAX_HOST_LENGTH = 253;
    /** One hostname label: 1–63 chars of {@code [a-z0-9]}, internal hyphens allowed, no leading/trailing hyphen. */
    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    /**
     * Held across "check the hosts are free" and "save the site", by every path that gives a site hosts, so two
     * concurrent saves can never both claim the same host. Held only around the check and the save, never
     * around slow work such as staging an uploaded backup.
     */
    public static final Object CLAIMS = new Object();

    private SiteHosts() {
    }

    /**
     * Lowercases and trims each requested host, drops blanks, de-duplicates (keeping first-seen order) and
     * validates every survivor as a dot-separated hostname. Rejects the whole list on the first malformed entry,
     * so a bad host is never persisted.
     *
     * @param requested the hosts as entered (may be {@code null})
     * @return the normalized hosts, never {@code null}
     * @throws IllegalArgumentException naming the first entry that is not a valid hostname (no scheme, port, path,
     *                                  wildcard or whitespace)
     */
    public static List<String> normalize(List<String> requested) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (requested != null) {
            for (String raw : requested) {
                if (raw == null) {
                    continue;
                }
                String host = raw.toLowerCase(Locale.ROOT).trim();
                if (host.isEmpty()) {
                    continue;
                }
                if (!isValid(host)) {
                    throw new IllegalArgumentException("'" + raw.trim() + "' is not a valid hostname");
                }
                out.add(host);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Rejects any of {@code hosts} that another site already answers on.
     *
     * @param sites  the site store to check against
     * @param siteId the site the hosts are for; its own current hosts never conflict
     * @param hosts  normalized hosts (see {@link #normalize})
     * @throws IllegalArgumentException naming the first host and the site that owns it
     */
    public static void requireUnclaimed(SiteStore sites, String siteId, List<String> hosts) {
        for (Site other : others(sites, siteId)) {
            for (String existing : other.hosts()) {
                if (hosts.contains(existing.toLowerCase(Locale.ROOT).trim())) {
                    throw new IllegalArgumentException(
                            "host '" + existing + "' already belongs to site '" + other.id() + "'");
                }
            }
        }
    }

    /**
     * The hosts of {@code siteId} that another site also claims — for a restore, whose hosts come from the archive
     * rather than from the operator, and may have been given to another site since the backup was taken.
     *
     * @return the conflicting hosts, in {@code hosts} order; empty when there is none
     */
    public static List<String> claimedElsewhere(SiteStore sites, String siteId, List<String> hosts) {
        List<String> taken = new ArrayList<>();
        for (Site other : others(sites, siteId)) {
            for (String existing : other.hosts()) {
                String normalized = existing.toLowerCase(Locale.ROOT).trim();
                for (String host : hosts) {
                    if (host.toLowerCase(Locale.ROOT).trim().equals(normalized) && !taken.contains(host)) {
                        taken.add(host);
                    }
                }
            }
        }
        return taken;
    }

    /**
     * Every other site whose record can be read. A site whose {@code site.yml} cannot be read is skipped: the
     * resolver cannot serve it either, so its hosts route nowhere, and one broken record must not block every
     * create and every host edit on the platform.
     */
    private static List<Site> others(SiteStore sites, String siteId) {
        List<Site> out = new ArrayList<>();
        for (String id : sites.ids()) {
            if (id.equals(siteId)) {
                continue;
            }
            try {
                sites.find(id).ifPresent(out::add);
            } catch (RuntimeException unreadable) {
                LOG.warn("site '{}' has an unreadable site.yml; its hosts are not checked for conflicts", id);
            }
        }
        return out;
    }

    /** Whether {@code host} (already lowercased and trimmed) is a syntactically valid dot-separated hostname. */
    static boolean isValid(String host) {
        if (host.length() > MAX_HOST_LENGTH || host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }
}
