package mx.quest.database.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import mx.quest.database.spi.DatabaseProvider;
import mx.quest.database.spi.ProviderSettings;

/**
 * Which provider the database plugin uses, and with which settings. The {@code provider} setting is {@code auto}
 * (the default: the one provider plugin installed), {@code postgresql} or {@code mariadb}; the server's settings
 * come from the chosen provider plugin (since 2.0.0).
 *
 * <p>Every problem is refused with a reason an operator can act on, in two kinds: {@link NoProviderException} when a
 * plugin must be installed or removed, plain {@link IllegalArgumentException} when a setting must change. Both are
 * fixed by a restart, which is what the runtime's status says.
 */
public final class ProviderChoice {

    /** The provider setting's value that picks the one installed provider. */
    public static final String AUTO = "auto";

    /** The provider to use and the checked settings to use it with. */
    public record Chosen(DatabaseProvider provider, DatabaseConfig config) {
    }

    /** A provider plugin is missing, or two offer the same product: install or remove one. */
    public static final class NoProviderException extends IllegalArgumentException {
        NoProviderException(String message) {
            super(message);
        }
    }

    private ProviderChoice() {
    }

    /**
     * The provider the running application uses, with its running settings.
     *
     * @throws NoProviderException      when the chosen provider plugin is not installed, or two are
     * @throws IllegalArgumentException when a setting is missing or unusable
     */
    public static Chosen running(DatabaseProps props, List<DatabaseProvider> installed) {
        DatabaseProvider provider = pick(props.getProvider(), installed);
        String plugin = plugin(provider.id());
        ProviderSettings server = provider.runningSettings().orElseThrow(() -> tooOld(plugin));
        if (server.isEmpty()) {
            List<String> moved = props.legacyKeys();
            if (!moved.isEmpty()) {
                throw new IllegalArgumentException("the server settings moved to the " + plugin + " plugin in "
                        + "database 2.0.0: move " + String.join(", ", moved) + " from plugin-settings/database.yml "
                        + "to plugin-settings/" + plugin + ".yml (or enter them on that plugin's settings page)");
            }
            throw new IllegalArgumentException("no server is set: enter it on the " + plugin
                    + " plugin's settings page");
        }
        return new Chosen(provider, check(props, provider, server));
    }

    /**
     * One installed provider with the settings saved for it now, for the connection test.
     *
     * @throws IllegalArgumentException when the saved settings are missing or unusable
     */
    public static Chosen saved(DatabaseProps props, DatabaseProvider provider) {
        String plugin = plugin(provider.id());
        ProviderSettings server = provider.savedSettings().orElseThrow(() -> tooOld(plugin));
        if (server.isEmpty()) {
            throw new IllegalArgumentException("no server is saved in the " + plugin + " plugin's settings");
        }
        return new Chosen(provider, check(props, provider, server));
    }

    /** The installed providers of products this plugin has SQL for, in the order found. */
    public static List<DatabaseProvider> supported(List<DatabaseProvider> installed) {
        return installed.stream().filter(p -> DatabaseConfig.PROVIDERS.contains(p.id())).toList();
    }

    /** The provider plugin's id for a product: {@code database-postgresql}, {@code database-mariadb}. */
    public static String plugin(String product) {
        return "database-" + product;
    }

    private static DatabaseProvider pick(String setting, List<DatabaseProvider> installed) {
        String choice = Optional.ofNullable(DatabaseConfig.trimToNull(setting)).orElse(AUTO).toLowerCase(Locale.ROOT);
        if (!choice.equals(AUTO) && !DatabaseConfig.PROVIDERS.contains(choice)) {
            throw new IllegalArgumentException("unknown provider: " + choice + " (auto, postgresql or mariadb)");
        }
        List<DatabaseProvider> candidates = supported(installed);
        if (choice.equals(AUTO)) {
            Set<String> products = new LinkedHashSet<>(candidates.stream().map(DatabaseProvider::id).toList());
            if (products.isEmpty()) {
                throw new NoProviderException("no provider plugin is installed: install database-postgresql or "
                        + "database-mariadb");
            }
            if (products.size() > 1) {
                throw new IllegalArgumentException("both provider plugins are installed: choose one in the provider "
                        + "setting");
            }
            choice = products.iterator().next();
        }
        String product = choice;
        List<DatabaseProvider> matching = candidates.stream().filter(p -> p.id().equals(product)).toList();
        if (matching.isEmpty()) {
            throw new NoProviderException("the provider plugin for '" + product + "' (" + plugin(product)
                    + ") is not installed");
        }
        if (matching.size() > 1) {
            throw new NoProviderException("two provider plugins offer '" + product + "'; remove one");
        }
        return matching.get(0);
    }

    private static DatabaseConfig check(DatabaseProps props, DatabaseProvider provider, ProviderSettings server) {
        try {
            return DatabaseConfig.from(props, provider.id(), server);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(plugin(provider.id()) + " settings: " + e.getMessage());
        }
    }

    private static IllegalArgumentException tooOld(String plugin) {
        return new NoProviderException("the " + plugin + " plugin is older than 2.0.0 and holds no server settings: "
                + "install " + plugin + " 2.0.0");
    }
}
