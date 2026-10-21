package mx.quest.runway.site;

/**
 * The operator-controlled lifecycle state of a site. (Tenant-controlled maintenance mode is a
 * separate, later concern; this is the application-realm suspend switch.)
 */
public enum SiteStatus {

    /** Live: the site is served normally. */
    ACTIVE,

    /** Suspended by an operator: public requests are short-circuited until it is resumed. */
    SUSPENDED
}
