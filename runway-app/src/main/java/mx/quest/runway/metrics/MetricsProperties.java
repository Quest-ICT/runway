package mx.quest.runway.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Observability configuration ({@code runway.metrics.*}).
 *
 * @param scrapeToken an optional bearer token that grants read-only access to {@code /actuator/prometheus}
 *                    without an operator session — for a monitoring system (Prometheus {@code authorization:
 *                    credentials}). Blank (the default) means <b>no token auth</b>: the endpoint is reachable
 *                    only by an authenticated application superadmin/admin, as before. When set, a request whose
 *                    {@code Authorization: Bearer <token>} matches (constant-time) is also allowed — nothing
 *                    else about the scrape surface changes.
 */
@ConfigurationProperties(prefix = "runway.metrics")
public record MetricsProperties(String scrapeToken) {

    public MetricsProperties {
        scrapeToken = (scrapeToken == null) ? "" : scrapeToken.trim();
    }

    /** Whether a scrape token is configured (so token-authenticated scraping is enabled). */
    public boolean hasScrapeToken() {
        return !scrapeToken.isEmpty();
    }
}
