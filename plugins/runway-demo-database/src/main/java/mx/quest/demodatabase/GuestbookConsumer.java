package mx.quest.demodatabase;

import java.util.Set;
import mx.quest.database.spi.DatabaseConsumer;
import mx.quest.database.spi.Scope;

/**
 * Declares that this plugin keeps data in the database: per site only. The database plugin checks the claim against
 * the jar this class was loaded from and reads the migration scripts under {@code db/runway-demo-database/} from it.
 */
public class GuestbookConsumer implements DatabaseConsumer {

    public static final String PLUGIN_ID = "runway-demo-database";

    @Override
    public String pluginId() {
        return PLUGIN_ID;
    }

    @Override
    public Set<Scope.Kind> scopes() {
        return Set.of(Scope.Kind.SITE);
    }
}
