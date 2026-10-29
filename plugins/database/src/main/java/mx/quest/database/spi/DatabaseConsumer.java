package mx.quest.database.spi;

import java.util.Set;

/**
 * A consumer plugin's declaration that it keeps data in the database. Implement it on one bean in your plugin's own
 * jar; the database plugin collects every {@code DatabaseConsumer} by type.
 *
 * <p>The declaration is <b>checked, not trusted</b>: the database plugin works out which plugin the bean's class was
 * loaded from (its jar's install folder, whose name the loader already requires to equal the plugin id) and refuses a
 * bean whose {@link #pluginId()} names a different plugin, or a second bean for the same id. Migration scripts are
 * then read only from that jar, under {@code db/<pluginId>/}, so two plugins on the shared class loader cannot pick
 * up each other's scripts.
 *
 * <p>The plugin id also decides the plugin's <b>object prefix</b>: every table, sequence, view, routine and trigger
 * its scripts create must start with it (see {@link PluginDatabase#prefix()}).
 */
public interface DatabaseConsumer {

    /**
     * The id from your {@code plugin.yml}.
     *
     * @return the plugin id, exactly as declared
     */
    String pluginId();

    /**
     * Where your data lives: {@link Scope.Kind#SITE} for data that belongs to one site (the usual case; it travels
     * in that site's backups), {@link Scope.Kind#APP} for data that belongs to no site. A scope not declared here
     * refuses to open, and its migration folder is ignored.
     *
     * @return a non-empty set
     */
    Set<Scope.Kind> scopes();
}
