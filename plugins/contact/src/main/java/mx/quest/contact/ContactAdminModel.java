package mx.quest.contact;

import java.util.List;
import mx.quest.runway.spi.service.SitePages;

/**
 * Read-only status model for the console surfaces (dashboard card + admin fragment), read by template bean name,
 * e.g. {@code ${@contactAdminModel.count(tenantSiteId)}} and iterated for the recent-submissions table. Also
 * exposes the SMTP <b>readiness</b> ({@link #smtpConfigured(String)}) and the test-action URL so the admin
 * fragment can show "Configured / Not configured" and offer the operator-only SMTP self-test, plus the
 * ready-made-page <b>install status</b> ({@link #pageInstalled(String)} / {@link #pagePath(String)}) — read
 * fresh from the kernel's {@link SitePages#pathOf} so a moved/renamed page reports its current path. Gated in the console
 * by the plugin's {@code settings} control point.
 */
public class ContactAdminModel {

    private static final int RECENT = 20;

    private final ContactStore store;
    private final ContactMailer mailer;
    private final SitePages pages;

    public ContactAdminModel(ContactStore store, ContactMailer mailer, SitePages pages) {
        this.store = store;
        this.mailer = mailer;
        this.pages = pages;
    }

    /** Total stored submissions for the site. */
    public int count(String siteId) {
        return store.count(siteId);
    }

    /** The most recent submissions (newest first) for the admin listing. */
    public List<ContactSubmission> recent(String siteId) {
        return store.recent(siteId, RECENT);
    }

    /**
     * Whether this site has a usable SMTP relay (a non-blank host AND recipient). When false, submissions are
     * stored only — the readiness badge shows "Not configured" and no email is sent.
     */
    public boolean smtpConfigured(String siteId) {
        return mailer.settingsFor(siteId).isConfigured();
    }

    /** The recipient address configured for this site (for display), or an empty string when none is set. */
    public String recipient(String siteId) {
        return mailer.settingsFor(siteId).to();
    }

    /**
     * Whether this site already has the ready-made contact page (the one {@code /contact/install-page} seeds).
     * Read fresh through {@link SitePages#pathOf} by the installer's stable slug, in any state (a draft or a page
     * waiting for review counts), so it stays true even after the operator moves or renames the page (the slug is
     * the page's identity; only its path changes).
     */
    public boolean pageInstalled(String siteId) {
        return pages.pathOf(siteId, ContactEndpoint.INSTALLED_PAGE_SLUG).isPresent();
    }

    /**
     * The current URL path of the installed contact page (e.g. {@code /contact}, or wherever the operator moved
     * it), or an empty string when it is not installed. Read live so the console always shows the real location.
     */
    public String pagePath(String siteId) {
        return pages.pathOf(siteId, ContactEndpoint.INSTALLED_PAGE_SLUG).orElse("");
    }
}
