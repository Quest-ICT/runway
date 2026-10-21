package mx.quest.runway.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound {@code runway.proxy.*} configuration for the trusted-proxy gate ({@link TrustedForwardedHeaderFilter}).
 *
 * <p>{@link #trustedCidrs} is the set of proxy peers whose {@code X-Forwarded-*} headers are honored. The
 * <b>bound default is empty</b> — trust nothing — so a directly-reachable instance never honors forwarded
 * headers unless an operator opts in. The shipped classpath {@code application.yml} seeds loopback (a same-host
 * proxy); an off-host proxy must add its exact IP/CIDR. An empty set with {@link #forwardedHeaders} on means
 * every request is attributed to its direct connection (logged at startup).
 */
@ConfigurationProperties("runway.proxy")
public class RunwayProxyProperties {

    /** Whether to honor {@code X-Forwarded-*} at all (still only from {@link #trustedCidrs}). Default true. */
    private boolean forwardedHeaders = true;

    /** Trusted proxy peer prefixes; forwarded headers are honored only from these. Bound default: empty. */
    private List<String> trustedCidrs = new ArrayList<>();

    public boolean isForwardedHeaders() {
        return forwardedHeaders;
    }

    public void setForwardedHeaders(boolean forwardedHeaders) {
        this.forwardedHeaders = forwardedHeaders;
    }

    public List<String> getTrustedCidrs() {
        return trustedCidrs;
    }

    public void setTrustedCidrs(List<String> trustedCidrs) {
        this.trustedCidrs = trustedCidrs;
    }
}
