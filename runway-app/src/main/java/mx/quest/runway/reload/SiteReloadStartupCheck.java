package mx.quest.runway.reload;

import mx.quest.runway.web.RunwayProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Warns, loudly and once at start-up, about the one configuration in which the reload endpoint's loopback gate
 * cannot do its job.
 *
 * <h2>The hazard</h2>
 * {@code SiteReloadFilter} refuses any caller whose address is not loopback. That address is
 * {@code request.getRemoteAddr()} <b>as it stands after</b> {@code TrustedForwardedHeaderFilter}, which rewrites
 * it to the real client only when the immediate peer is inside {@code runway.proxy.trusted-cidrs}. From an
 * <b>untrusted</b> peer that filter hides the forwarded headers and <b>lets the raw peer address stand</b>.
 *
 * <p>Normally that is exactly right. But a reverse proxy can be configured to originate its upstream connections
 * from loopback — HAProxy's {@code source 127.0.0.1} is the case that prompted this check — and then <b>every</b>
 * proxied request reaches the app from {@code 127.0.0.1}. If that proxy is not in {@code trusted-cidrs}, the raw
 * peer stands, it is loopback, and the gate opens for traffic that actually came from the internet. The endpoint
 * would still be behind its bearer token, so this is a defence-in-depth layer failing rather than an open door —
 * but the layer is the reason the endpoint needs no firewall rule, so losing it silently is not acceptable.
 *
 * <p>We cannot detect the dangerous case directly: a trusted proxy's headers are stripped and an untrusted one's
 * are hidden, so by the time any request arrives there is no evidence a proxy exists. What we <em>can</em> see is
 * the configuration that makes it possible — forwarded handling on, trusted proxies empty — which is also the
 * configuration of a perfectly ordinary install with no proxy at all. So this warns rather than refuses: turning
 * the endpoint off here would break the common, safe case to protect the rare one.
 */
public class SiteReloadStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(SiteReloadStartupCheck.class);

    private final ReloadProperties reload;
    private final RunwayProxyProperties proxy;

    public SiteReloadStartupCheck(ReloadProperties reload, RunwayProxyProperties proxy) {
        this.reload = reload;
        this.proxy = proxy;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfTheLoopbackGateCannotBeTrusted() {
        if (!reload.active()) {
            return; // endpoint absent — nothing to warn about
        }
        if (!proxy.getTrustedCidrs().isEmpty()) {
            log.info("Site reload endpoint armed at /__reload (loopback-only, token-gated).");
            return;
        }
        log.warn("Site reload endpoint is armed at /__reload, but runway.proxy.trusted-cidrs is EMPTY. Its "
                + "loopback-only gate trusts the connection's peer address. That is correct for a directly-exposed "
                + "instance — but if a reverse proxy fronts this one AND originates upstream connections from "
                + "loopback (e.g. HAProxy 'source 127.0.0.1'), every proxied request arrives from 127.0.0.1 and "
                + "the gate will admit internet traffic, leaving only the bearer token in front of it. If a proxy "
                + "IS in front: set runway.proxy.trusted-cidrs to the proxy's address so the real client address "
                + "is resolved, or block /__reload at the proxy, or unset runway.reload.token to disable the "
                + "endpoint.");
    }
}
