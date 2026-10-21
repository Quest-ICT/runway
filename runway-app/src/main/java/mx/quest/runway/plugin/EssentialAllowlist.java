package mx.quest.runway.plugin;

import java.util.Set;

/**
 * The kernel-internal allowlist of <b>essential</b> (security-floor) plugin ids. A plugin is treated as
 * essential — its failure to load drives fail-closed degraded mode — <b>only</b> if its id is here; the
 * descriptor's {@code essential: true} is advisory and is <b>ignored with a warning</b> for any id not on this
 * list (a feature plugin can never promote itself into the fail-closed set). The list is code,
 * never data.
 */
public final class EssentialAllowlist {

    /** The security-floor plugins: the essential protection plugin (DoS guard + login brute-force lockout). */
    private static final Set<String> IDS = Set.of("protection");

    private EssentialAllowlist() {
    }

    /** Whether {@code pluginId} is an allowlisted essential (security-floor) plugin. */
    public static boolean contains(String pluginId) {
        return IDS.contains(pluginId);
    }

    /**
     * What the operator does when the essential plugin {@code pluginId} is refused or fails to load: put back the copy
     * that ships with the running Runway. Appended to such a plugin's blocked reason (the plugins screen prints it in
     * the row) and to the fail-closed log line, in place of the advice a feature plugin gets — "install another
     * version of the plugin, or remove it" is wrong for this one: no other release of it exists outside a Runway
     * bundle, and removing it runs the sites without their abuse floor.
     *
     * <p>The paths are the installer's, relative to the Runway home ({@code /opt/runway} on a default install — the
     * "current" symlink the service runs from, which is also the working directory the {@code external/} path is
     * read against). The bundle carries the plugin under {@code seed/plugins/<id>/}, and the installer keeps that
     * folder in the release directory, so the repair needs no download: copy it over {@code external/plugins/<id>/}.
     * After a rollback the symlink names the older release, whose {@code seed/} is the older plugin — the one that
     * kernel needs, the same files as the {@code *.bak} pair the upgrade kept. The fallbacks cover the two cases
     * where {@code seed/} is not the running release's copy: a release whose installer did not keep {@code seed/} has
     * none, and a kernel jar replaced by hand left the earlier release's in place — then the operator extracts the
     * running release's {@code .bin}.
     *
     * @param pluginId an allowlisted essential plugin id
     * @return one or more complete sentences, never {@code null}
     */
    public static String repairAdvice(String pluginId) {
        return "It is Runway's essential '" + pluginId + "' plugin, which ships with each Runway release, so it "
                + "must be the copy from the running release: copy seed/plugins/" + pluginId + "/ over "
                + "external/plugins/" + pluginId + "/, both in the Runway home (/opt/runway on a default install; "
                + "after a rollback its seed/ is the older release's copy, the one it needs), or re-run that "
                + "release's installer (sudo ./runway-<version>.bin -y — every upgrade refreshes it). If there is no "
                + "seed/ folder there (an older install) or you replaced the kernel jar by hand, "
                + "extract the running release (./runway-<version>.bin --extract DIR) and copy "
                + "DIR/runway-<version>/seed/plugins/" + pluginId + "/ instead; after a rollback to a release without "
                + "seed/, restore the plugin.yml.bak and *.jar.bak pair in external/plugins/" + pluginId + "/. Then "
                + "restart. Until it loads, every tenant page answers 503 (fail-closed); the application console "
                + "stays reachable.";
    }
}
