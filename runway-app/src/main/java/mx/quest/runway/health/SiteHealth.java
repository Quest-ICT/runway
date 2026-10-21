package mx.quest.runway.health;

/**
 * The composed health verdict for one site — an immutable value with a <b>closed vocabulary</b>, so the JSON key
 * and token set of {@code /__health} is stable by construction rather than by convention. Callers outside this
 * package see only these enums; the wire shape is assembled in {@code SiteHealthFilter}.
 *
 * @param status the coarse, PUBLIC verdict — the only part an unauthenticated probe ever sees
 * @param state  the specific cause, operator-only (never public: it asserts a business decision about a tenant)
 * @param content whether the site's templates are present enough to render at all
 * @param admin  whether the site's own admin surface is usable (never affects {@link #status} beyond DEGRADED)
 */
public record SiteHealth(Status status, State state, ContentCheck content, AdminCheck admin) {

    /** The public verdict. Three tokens: enough to alert on, carrying no business fact. */
    public enum Status {
        /** Serving normally. */
        UP,
        /** Serving public traffic, but something warrants attention (admin quarantined, or templates missing). */
        DEGRADED,
        /** Not serving public traffic. */
        DOWN
    }

    /** The specific cause behind {@link Status} — operator-only. */
    public enum State {
        ACTIVE,
        MAINTENANCE,
        SUSPENDED,
        OVER_QUOTA,
        /** The platform's essential-plugin security floor is degraded; every bound tenant request 503s. */
        PLATFORM_DEGRADED
    }

    /** Whether the site has the template files it needs to render its home page. */
    public enum ContentCheck {
        OK,
        /** No {@code _master.html} — every page that pulls the master fails to render. */
        MISSING_MASTER,
        /** No home-page template — the site's root 404s or errors. */
        MISSING_HOME,
        /** Not evaluated ({@code runway.health.content-check: false}). */
        SKIPPED
    }

    /** Whether the tenant's own console is reachable. */
    public enum AdminCheck {
        OK,
        /** The tenancy's role store failed startup validation; {@code /__admin} is 503'd until repaired+restarted. */
        QUARANTINED
    }

    /** Whether this verdict means the site is not serving public traffic (drives the 503). */
    public boolean down() {
        return status == Status.DOWN;
    }

    /**
     * Whether a {@code Retry-After} is appropriate — true only for the time-bounded blocks. Suspension and a
     * degraded platform floor are indefinite operator actions, so promising a retry window would be a lie; this
     * mirrors exactly what a visitor already gets from {@code MaintenanceSiteFilter}/{@code BandwidthLimitFilter}
     * (which do send one) versus {@code SuspendedSiteFilter}/{@code EssentialFloorFilter} (which do not).
     */
    public boolean retryable() {
        return state == State.MAINTENANCE || state == State.OVER_QUOTA;
    }
}
