package mx.quest.database.spi;

/**
 * The database plugin's entry point for consumers: inject it, and ask for your own handle.
 */
public interface Databases {

    /**
     * The handle for the plugin {@code me} belongs to.
     *
     * @param me your {@link DatabaseConsumer} bean
     * @return your handle (the same instance on every call)
     * @throws IllegalArgumentException when {@code me} was not registered as a bean, or its class is not from the
     *                                  plugin its {@link DatabaseConsumer#pluginId()} names
     */
    PluginDatabase of(DatabaseConsumer me);
}
