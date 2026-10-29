package mx.quest.database.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import mx.quest.database.spi.DatabaseConsumer;
import mx.quest.database.spi.Databases;
import mx.quest.database.spi.PluginDatabase;

/**
 * Hands each consumer plugin its handle, after proving which plugin the {@link DatabaseConsumer} bean belongs to:
 * the jar its class was loaded from must sit in a folder named after the id it claims, and a plugin gets one bean.
 */
public final class DatabasesImpl implements Databases {

    private final DatabaseCore core;
    private final BiFunction<Class<?>, String, ScriptSource> locator;
    private final Map<String, DatabaseConsumer> beans = new ConcurrentHashMap<>();
    private final Map<String, PluginDatabaseImpl> handles = new ConcurrentHashMap<>();

    /** With the real check: scripts come from the bean's own jar. */
    public DatabasesImpl(DatabaseCore core) {
        this(core, ConsumerJar::of);
    }

    /**
     * @param locator finds (and proves) a bean's script source from its class and claimed plugin id; throws
     *                {@link IllegalArgumentException} when the class does not belong to that plugin
     */
    public DatabasesImpl(DatabaseCore core, BiFunction<Class<?>, String, ScriptSource> locator) {
        this.core = Objects.requireNonNull(core, "core");
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    @Override
    public synchronized PluginDatabase of(DatabaseConsumer me) {
        Objects.requireNonNull(me, "me");
        String pluginId = Objects.requireNonNull(me.pluginId(), "pluginId");
        PluginDatabaseImpl handle = handles.get(pluginId);
        if (handle != null) {
            if (beans.get(pluginId) != me) {
                throw new IllegalArgumentException("plugin '" + pluginId + "' already has a DatabaseConsumer bean");
            }
            return handle;
        }
        ScriptSource source = locator.apply(me.getClass(), pluginId);
        Consumer consumer = Consumer.of(pluginId, me.scopes(), source);
        handle = new PluginDatabaseImpl(core, consumer);
        beans.put(pluginId, me);
        handles.put(pluginId, handle);
        return handle;
    }

    /** The consumers registered so far. */
    public List<Consumer> consumers() {
        return handles.values().stream().map(PluginDatabaseImpl::consumer).toList();
    }

    /** Registers every collected consumer bean, logging (not throwing) the ones refused. */
    public void registerAll(Collection<DatabaseConsumer> collected, java.util.function.Consumer<String> refused) {
        for (DatabaseConsumer bean : collected) {
            try {
                of(bean);
            } catch (RuntimeException e) {
                refused.accept(bean.getClass().getName() + ": " + e.getMessage());
            }
        }
    }
}
