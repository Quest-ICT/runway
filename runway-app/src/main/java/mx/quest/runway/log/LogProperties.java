package mx.quest.runway.log;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level log settings ({@code runway.logs.*}) — deliberately <b>app-level</b>, not tenant-editable: a
 * tenant-settable queue size or retention ceiling would be an abuse lever. Each site's own retention preference
 * (in {@code log.yml}) is clamped to {@link #getMaxRetentionDays()}.
 */
@ConfigurationProperties("runway.logs")
public class LogProperties {

    /** Whether per-site log capture runs at all. Default: on. */
    private boolean enabled = true;

    /** The upper bound a site's own retention preference is clamped to. */
    private int maxRetentionDays = 90;

    /** The bounded capture-queue size; a full queue drops events (counted) rather than blocking a request. */
    private int queueCapacity = 8192;

    /**
     * Whether the public access log appends the request's {@code User-Agent}. Default {@code false}.
     *
     * <p>Off by default on purpose: this log carries one line per public request, so the field is pure growth
     * on a busy site, and an upgrade should never silently change the size or shape of an operator's log
     * stream — anything parsing it positionally would break. Turn it on when you need to tell a crawler from a
     * browser in the app's own log.
     *
     * <p>Independent of edge-rejection logging, which <b>always</b> records the agent
     * ({@code runway.protect}): rejections are rare by construction and undiagnosable without it, whereas this
     * is high-volume and usually answerable upstream at a proxy.
     *
     * <p>The value is untrusted client input; it is sanitised and truncated before it reaches a log line.
     */
    private boolean accessLogUserAgent = false;

    public boolean isAccessLogUserAgent() {
        return accessLogUserAgent;
    }

    public void setAccessLogUserAgent(boolean accessLogUserAgent) {
        this.accessLogUserAgent = accessLogUserAgent;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRetentionDays() {
        return maxRetentionDays;
    }

    public void setMaxRetentionDays(int maxRetentionDays) {
        this.maxRetentionDays = maxRetentionDays;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
