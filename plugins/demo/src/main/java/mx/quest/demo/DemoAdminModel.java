package mx.quest.demo;

import java.util.List;
import mx.quest.runway.spi.RunwaySpi;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.spi.service.Sites;

/**
 * Backs the plugin's console fragments — the dashboard card ({@code templates/dashboard.*}) and the admin fragment
 * under the settings form ({@code templates/admin.*}). The host does not push data into a fragment; the fragment's
 * decoupled logic reads this bean by name, {@code ${@runwayDemoAdminModel.noteCount(tenantSiteId)}}, with
 * {@code tenantSiteId} supplied by the console (and {@code null} on the application console, so every accessor is
 * null-safe and a fragment never errors).
 *
 * <p>What a fragment may rely on is a contract: the variables {@code tenantSiteId} and {@code _csrf}, the expressions
 * {@code ${@<own bean>…}} and {@code ${@authz.may('…')}}, and {@code #{…}} messages. Nothing else in the console's
 * model is promised — so anything else a fragment shows comes from a bean like this one.
 *
 * <p>It shows three services in their read-only roles: {@link Sites} (a site's view from its id, off-request — the
 * fragment only has the id), {@link SitePages#pathOf} (where "our" starter page lives now, whatever its state, even
 * after an operator moved it) and {@link PluginSettings#secret} (whether a per-site secret is set — answered without
 * ever exposing the value). {@link PluginInfo} is this plugin's identity.
 */
public class DemoAdminModel {

    private static final int RECENT = 5;

    private final DemoNotesStore notes;
    private final DemoAppProps props;
    private final PluginSettings settings;
    private final Sites sites;
    private final SitePages pages;
    private final PluginInfo self;

    public DemoAdminModel(DemoNotesStore notes, DemoAppProps props, PluginSettings settings, Sites sites,
                          SitePages pages, PluginInfo self) {
        this.notes = notes;
        this.props = props;
        this.settings = settings;
        this.sites = sites;
        this.pages = pages;
        this.self = self;
    }

    private static boolean noSite(String siteId) {
        return siteId == null || siteId.isBlank();
    }

    public int noteCount(String siteId) {
        return noSite(siteId) ? 0 : notes.count(siteId);
    }

    /** The newest notes, newest first, for the admin fragment's list. */
    public List<Note> recentNotes(String siteId) {
        return noSite(siteId) ? List.of() : notes.recent(siteId, RECENT);
    }

    /** The site's display name, from {@link Sites#find}; the id itself when the site is unknown. */
    public String siteName(String siteId) {
        return noSite(siteId) ? "" : sites.find(siteId).map(SiteView::displayName).orElse(siteId);
    }

    /** Where the demo's starter page lives now (any state), or {@code ""} when it is not installed. */
    public String demoPagePath(String siteId) {
        return noSite(siteId) ? "" : pages.pathOf(siteId, DemoConsoleController.STARTER_SLUG).orElse("");
    }

    /** Whether the per-site {@code analytics.site-token} secret is set; the value itself never leaves the plugin. */
    public boolean siteTokenConfigured(String siteId) {
        return !noSite(siteId) && settings.secret(siteId, "analytics.site-token").isPresent();
    }

    /** This plugin's version, from {@code plugin.yml} (via {@link PluginInfo}). */
    public String pluginVersion() {
        return self.version();
    }

    /** The SPI version this plugin was compiled against (a constant the compiler inlined). */
    public String builtSpi() {
        return RunwaySpi.VERSION;
    }

    /** The SPI version of the kernel this plugin is running on. */
    public String runningSpi() {
        return RunwaySpi.runtimeVersion();
    }

    public boolean bannerEnabled() {
        return props.getBanner().isEnabled();
    }

    public boolean analyticsKeyConfigured() {
        return props.getAnalytics().hasKey();
    }
}
