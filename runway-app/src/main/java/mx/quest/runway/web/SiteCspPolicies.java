package mx.quest.runway.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import mx.quest.runway.site.SiteCspService;

/**
 * The Content Security Policy template for one site's public pages: the platform policy plus the approved embed hosts
 * the site switched on ({@link SiteCspService#additions}), or {@code null} when it switched none on (the platform
 * policy applies unchanged).
 *
 * <p>Built templates are cached by their additions, not by site, so sites with the same choices share one and a
 * change in either the operator's list or a site's choice simply produces a different key. The cache is cleared if
 * it ever grows past {@link #MAX_CACHED} distinct combinations.
 */
public final class SiteCspPolicies implements Function<String, String> {

    static final int MAX_CACHED = 1000;

    private final RunwayCspProperties properties;
    private final List<String> pluginHosts;
    private final SiteCspService sites;
    private final Map<Map<String, List<String>>, String> built = new ConcurrentHashMap<>();

    public SiteCspPolicies(RunwayCspProperties properties, List<String> pluginHosts, SiteCspService sites) {
        this.properties = properties;
        this.pluginHosts = List.copyOf(pluginHosts);
        this.sites = sites;
    }

    /** The policy template for {@code siteId}'s public pages, or {@code null} for the platform policy. */
    @Override
    public String apply(String siteId) {
        Map<String, List<String>> additions = sites.additions(siteId);
        if (additions.isEmpty()) {
            return null;
        }
        if (built.size() > MAX_CACHED) {
            built.clear();
        }
        return built.computeIfAbsent(Map.copyOf(additions), key -> properties.buildPolicy(pluginHosts, key));
    }
}
