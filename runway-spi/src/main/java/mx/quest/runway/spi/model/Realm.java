package mx.quest.runway.spi.model;

/**
 * One of Runway's two isolated identity domains. The realms never cross: authenticating in
 * one never grants access to the other, and a contribution (a permission, a menu entry, an
 * endpoint route) always declares which realm it belongs to.
 */
public enum Realm {

    /**
     * The platform-operator realm — the people who run the Runway server itself (configure
     * the platform, manage plugins, create and suspend sites, manage application users).
     */
    APPLICATION,

    /**
     * The per-site (tenant) realm — the people who manage an individual site (its pages,
     * users, content, and plugin configuration). A tenant-realm actor can never reach another
     * site's data or the platform's configuration.
     */
    TENANT
}
