package mx.quest.runway.recovery;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.web.ConsoleHostFilter;
import mx.quest.runway.web.ConsoleHostProperties;
import mx.quest.runway.web.RequestOrigin;
import org.springframework.stereotype.Component;

/**
 * Where an emailed password-reset link points: {@code scheme://host[:port]}, from what the platform was told, never
 * from the host the request names.
 *
 * <p><b>Why.</b> Anyone can send a request with any {@code Host} header (or {@code X-Forwarded-Host}, through a
 * proxy that passes it on). A link built from it would be a genuine mail from this platform, sent to a real user, that
 * hands the single-use token to whatever host the sender chose; one click and the account is theirs. So:
 * <ul>
 *   <li><b>Site realm:</b> the site's own base URL, configured or derived from its first host name
 *       ({@link SiteViews#toView}). A link for a site always points at that site, whichever host the request came in
 *       on (an unknown host reaches the default site, for instance). A site with no host name has no address to
 *       mail: no link. Nor does one whose only address is derived from a loopback name ({@code localhost}, as the
 *       setup wizard leaves the default site): {@code https://localhost} reaches nobody who reads the mail.</li>
 *   <li><b>Application realm:</b> when its host is one of {@code runway.console.hosts} (the console answers on no
 *       other host then, so the name was checked), that entry's host name, the request's scheme, and the entry's port
 *       if it names one, else the request's; otherwise the default site's base URL, on which the console also
 *       answers; otherwise no link.</li>
 * </ul>
 * With no link, recovery is not offered on that address (the login page hides "Forgot your password?") and a request
 * sends nothing.
 */
@Component
public class RecoveryLinks {

    private final SiteStore sites;
    private final PlatformSettingsStore platform;
    private final List<ConsoleHost> consoleHosts;

    /** A {@code runway.console.hosts} entry: its normalized host name, and its port, or {@code -1} when it names none. */
    record ConsoleHost(String host, int port) {
    }

    public RecoveryLinks(SiteStore sites, PlatformSettingsStore platform, ConsoleHostProperties console) {
        this.sites = sites;
        this.platform = platform;
        this.consoleHosts = console.getHosts() == null ? List.of() : console.getHosts().stream()
                .map(RecoveryLinks::consoleHost).filter(h -> !h.host().isEmpty()).toList();
    }

    static ConsoleHost consoleHost(String entry) {
        String host = ConsoleHostFilter.normalize(entry);
        String rest = entry.strip();
        int scheme = rest.indexOf("://");
        rest = scheme >= 0 ? rest.substring(scheme + 3) : rest;
        int slash = rest.indexOf('/');
        rest = slash >= 0 ? rest.substring(0, slash) : rest;
        int port = -1;
        try {
            port = URI.create("x://" + rest).getPort();
        } catch (IllegalArgumentException notAnAuthority) {
            // an unbracketed IPv6 literal, for instance: it names no port
        }
        return new ConsoleHost(host, port);
    }

    /** The origin of an application-realm reset link for {@code request}, or empty when none can be trusted. */
    public Optional<String> appBase(HttpServletRequest request) {
        if (!consoleHosts.isEmpty()) {
            String host = ConsoleHostFilter.normalize(request.getServerName());
            String scheme = request.getScheme();
            if (scheme == null || scheme.isBlank()) {
                return Optional.empty();
            }
            // The listed name, not the request's spelling of it (case, a trailing dot); a listed port bounds the
            // request's, which is whatever the client wrote in its Host header.
            return consoleHosts.stream().filter(entry -> entry.host().equals(host)).findFirst()
                    .map(entry -> RequestOrigin.origin(scheme, entry.host(),
                            entry.port() >= 0 ? entry.port() : request.getServerPort()));
        }
        return platform.get().defaultSite().flatMap(sites::find).flatMap(this::siteBase);
    }

    /**
     * The origin of a site-realm reset link for {@code site}, or empty when the site has no address for one: no host
     * name, or no base URL and a loopback first host.
     */
    public Optional<String> siteBase(Site site) {
        boolean configured = site.baseUrl() != null && !site.baseUrl().isBlank();
        if (!configured && (site.hosts().isEmpty() || isLoopback(site.hosts().get(0)))) {
            return Optional.empty();
        }
        String base = SiteViews.toView(site).baseUrl();
        return base == null || base.isBlank() ? Optional.empty() : Optional.of(RequestOrigin.trusted(base, null));
    }

    /** {@code localhost} (or a name under it), {@code 127.x.x.x} or {@code ::1}: a name only this machine reaches. */
    static boolean isLoopback(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.matches("127(\\.\\d{1,3}){3}")
                || h.equals("::1") || h.equals("[::1]");
    }
}
