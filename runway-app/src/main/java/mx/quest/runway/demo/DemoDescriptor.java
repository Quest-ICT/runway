package mx.quest.runway.demo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;

/**
 * The descriptor of a bundled demo site — parsed from a demo's classpath {@code seed/demo/<id>/demo.yml}.
 * It carries only the facts the {@link DemoSiteInstaller} needs to <em>create</em> the tenant (identity,
 * hosts, languages, the superadmin login shell, whether it is the platform default); the actual site
 * content lives as ordinary files alongside the descriptor and is copied verbatim into the tenant folder.
 *
 * <p>The {@code default} YAML key maps to {@link #defaultSite()} (a Java-keyword rename). Lists may be
 * {@code null} in a hand-written file; call the null-safe accessors ({@link #hostList()},
 * {@link #localeList()}) rather than the raw components.
 *
 * @param id              the tenant id the demo installs as (also the folder name and the classpath subdir)
 * @param name            the site display name
 * @param hosts           the default host(s) the site is created with (operator-overridable at install)
 * @param defaultLocale   the default language tag (e.g. {@code en})
 * @param locales         the offered language tags (default first)
 * @param defaultSite     when {@code true}, the installer also points the platform catch-all default at this site
 * @param master          the tenant superadmin shell (username + display name; password supplied at install)
 * @param requiresPlugins informational: plugins whose features light up for this demo (widgets soft-degrade if absent)
 */
public record DemoDescriptor(String id, String name, List<String> hosts, String defaultLocale,
                             List<String> locales, @JsonProperty("default") boolean defaultSite,
                             Master master, List<String> requiresPlugins) {

    /** The tenant superadmin shell for a demo: its login name and display name (password is supplied at install). */
    public record Master(String username, String displayName) {
    }

    /** The offered locales as {@link Locale}s (default first); falls back to English-only if none declared. */
    public List<Locale> localeList() {
        if (locales == null || locales.isEmpty()) {
            return List.of(defaultLocaleOrEnglish());
        }
        return locales.stream().map(Locale::forLanguageTag).toList();
    }

    /** The default locale (or English when the descriptor omits it). */
    public Locale defaultLocaleOrEnglish() {
        return (defaultLocale == null || defaultLocale.isBlank())
                ? Locale.ENGLISH : Locale.forLanguageTag(defaultLocale);
    }

    /** The declared default hosts (never {@code null}). */
    public List<String> hostList() {
        return hosts == null ? List.of() : List.copyOf(hosts);
    }

    /** The first declared default host, or the empty string when none is declared. */
    public String defaultHost() {
        return hostList().isEmpty() ? "" : hostList().get(0);
    }

    /** The declared plugin dependencies (never {@code null}). */
    public List<String> requiredPlugins() {
        return requiresPlugins == null ? List.of() : List.copyOf(requiresPlugins);
    }

    /** The master username, or {@code "demo"} when the descriptor omits it. */
    public String masterUsername() {
        return (master == null || master.username() == null || master.username().isBlank())
                ? "demo" : master.username();
    }

    /** The master display name, or a sensible default. */
    public String masterDisplayName() {
        return (master == null || master.displayName() == null || master.displayName().isBlank())
                ? name + " admin" : master.displayName();
    }
}
