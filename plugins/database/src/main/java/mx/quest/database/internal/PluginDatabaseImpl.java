package mx.quest.database.internal;

import java.util.Objects;
import mx.quest.database.spi.DatabaseUnavailableException;
import mx.quest.database.spi.Dialect;
import mx.quest.database.spi.PluginDatabase;
import mx.quest.database.spi.Scope;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.spi.model.SiteView;

/** One consumer plugin's handle, handed out by {@link DatabasesImpl}. */
final class PluginDatabaseImpl implements PluginDatabase {

    private final DatabaseCore core;
    private final Consumer consumer;

    PluginDatabaseImpl(DatabaseCore core, Consumer consumer) {
        this.core = core;
        this.consumer = consumer;
    }

    Consumer consumer() {
        return consumer;
    }

    @Override
    public boolean available() {
        return core.runtime().available();
    }

    @Override
    public Dialect dialect() {
        Engine engine = core.runtime().engine();
        if (engine == null || !available()) {
            throw new DatabaseUnavailableException("the database is not available");
        }
        return engine.dialect();
    }

    @Override
    public String prefix() {
        return consumer.prefix();
    }

    @Override
    public Scope currentSite() {
        SiteView site = core.requestContext().site().orElseThrow(() -> new IllegalStateException(
                "there is no site in this request (an application-console request or a background thread)"));
        return new ScopeImpl(core, consumer, Scope.Kind.SITE, site.id());
    }

    @Override
    public Scope site(SiteRef site) {
        Objects.requireNonNull(site, "site");
        return siteInBackground(site.id());
    }

    @Override
    public Scope siteInBackground(String siteId) {
        Objects.requireNonNull(siteId, "siteId");
        if (core.sites().find(siteId).isEmpty()) {
            throw new IllegalArgumentException("no site with id " + siteId);
        }
        return new ScopeImpl(core, consumer, Scope.Kind.SITE, siteId);
    }

    @Override
    public Scope app() {
        return new ScopeImpl(core, consumer, Scope.Kind.APP, null);
    }
}
