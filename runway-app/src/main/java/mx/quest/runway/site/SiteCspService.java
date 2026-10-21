package mx.quest.runway.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.platform.PlatformSettings.EmbedHost;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Which of the operator's approved embed hosts ({@link EmbedHost}) a site has switched on for its own public pages,
 * kept in {@code sites/<id>/csp.yml}, and what that adds to each Content Security Policy directive.
 *
 * <p><b>The operator's list always decides.</b> A site's file names host ids; only ids the operator still approves
 * count, with the directives the operator allows them. So withdrawing an approval takes effect on every site at
 * once, and a restored or imported backup that names other ids widens nothing.
 */
@Service
public class SiteCspService {

    private static final Logger log = LoggerFactory.getLogger(SiteCspService.class);
    private static final String FILE = "csp.yml";

    /** What {@code csp.yml} holds: the chosen host ids. */
    public record SiteCsp(List<String> hosts) {
        public SiteCsp {
            hosts = hosts == null ? List.of() : List.copyOf(hosts);
        }
    }

    private final StorageLayout layout;
    private final YamlStore yaml;
    private final PlatformSettingsStore platform;
    private final Map<String, List<String>> chosen = new ConcurrentHashMap<>();

    public SiteCspService(StorageLayout layout, YamlStore yaml, PlatformSettingsStore platform) {
        this.layout = layout;
        this.yaml = yaml;
        this.platform = platform;
    }

    /** The host ids the site has switched on, as stored (some may no longer be approved). */
    public List<String> chosen(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            return List.of();
        }
        return chosen.computeIfAbsent(siteId, this::load);
    }

    /** The approved hosts the site has switched on, in the operator's order. */
    public List<EmbedHost> active(String siteId) {
        Set<String> ids = new LinkedHashSet<>(chosen(siteId));
        if (ids.isEmpty()) {
            return List.of();
        }
        return platform.get().embedHosts().stream().filter(host -> ids.contains(host.id())).toList();
    }

    /**
     * What the site's choices add to each directive: directive → sources, in the operator's order. Empty when the
     * site has switched nothing on (then its pages get the platform policy unchanged).
     */
    public Map<String, List<String>> additions(String siteId) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (EmbedHost host : active(siteId)) {
            for (String directive : host.directives()) {
                out.computeIfAbsent(directive, d -> new ArrayList<>()).add(host.source());
            }
        }
        return out;
    }

    /**
     * Saves the site's choice. Ids the operator does not approve are dropped, so the file only ever names hosts that
     * existed when it was saved.
     */
    public void save(String siteId, List<String> hostIds) {
        Set<String> approved = new LinkedHashSet<>();
        platform.get().embedHosts().forEach(host -> approved.add(host.id()));
        List<String> keep = (hostIds == null ? List.<String>of() : hostIds).stream()
                .filter(approved::contains).distinct().toList();
        try {
            yaml.write(file(siteId), new SiteCsp(keep));
        } catch (IOException e) {
            throw new UncheckedIOException("could not save " + FILE + " for site " + siteId, e);
        }
        chosen.remove(siteId);
    }

    /** A restored or deleted site re-reads its file. */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            chosen.remove(event.siteId());
        }
    }

    private List<String> load(String siteId) {
        try {
            return yaml.read(file(siteId), SiteCsp.class).map(SiteCsp::hosts).orElse(List.of());
        } catch (IOException | RuntimeException e) {
            log.warn("could not read {} for site '{}': {}; its pages get the platform policy", FILE, siteId,
                    e.toString());
            return List.of();
        }
    }

    private Path file(String siteId) {
        return layout.siteDir(PathSafety.requireSafeId(siteId)).resolve(FILE);
    }
}
