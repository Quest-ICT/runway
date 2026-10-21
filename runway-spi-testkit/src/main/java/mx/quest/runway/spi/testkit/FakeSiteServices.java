package mx.quest.runway.spi.testkit;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.spi.service.Sites;

/**
 * One configurable fake for the SPI 0.5.0 kernel singletons a plugin may inject — {@link Sites},
 * {@link RequestContext}, {@link AccessCheck}, {@link Messages} and {@link SitePages} — so a plugin test sets up "the
 * kernel" in a few lines and hands this object to whichever constructor parameters need it.
 *
 * <pre>{@code
 * FakeSiteServices kernel = new FakeSiteServices()
 *         .site(new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test"))
 *         .current("acme")
 *         .grant("contact|settings")
 *         .message("acme", "contact.sent", "Thanks!");
 * kernel.nextInstall(InstallResult.Outcome.PENDING_REVIEW);
 * }</pre>
 *
 * The input rules match the kernel's: an unsafe site id or slug throws {@link IllegalArgumentException};
 * {@code may} is fail-closed (only granted keys answer {@code true}); a missing message answers its fallback, or the
 * code; {@code install} never overwrites a page it has already installed. Thread-safe.
 */
public final class FakeSiteServices implements Sites, RequestContext, AccessCheck, Messages, SitePages {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Map<String, SiteView> sites = new ConcurrentHashMap<>();
    private volatile String current;
    private final Set<String> granted = ConcurrentHashMap.newKeySet();
    private final Map<String, String> messages = new ConcurrentHashMap<>();
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private final List<StarterPage> installs = new CopyOnWriteArrayList<>();
    private volatile InstallResult.Outcome nextOutcome = InstallResult.Outcome.PUBLISHED;
    private volatile List<Finding> nextBlocking = List.of();

    /** Adds a site {@link #find} knows. */
    public FakeSiteServices site(SiteView site) {
        sites.put(Objects.requireNonNull(site, "site").id(), site);
        return this;
    }

    /** Binds the "current request" to a site added with {@link #site} ({@code null}: off a request / app realm). */
    public FakeSiteServices current(String siteId) {
        this.current = siteId;
        return this;
    }

    /** Grants a permission key to the "current principal". */
    public FakeSiteServices grant(String permissionKey) {
        granted.add(permissionKey);
        return this;
    }

    /** A message for {@code site(siteId, …)}; {@code siteId} {@code null} defines a console message. */
    public FakeSiteServices message(String siteId, String code, String text) {
        messages.put((siteId == null ? "" : siteId) + "|" + code, text);
        return this;
    }

    /** An existing page (any state) at {@code path}, as {@link #pathOf} reports it. */
    public FakeSiteServices page(String siteId, String slug, String path) {
        pages.put(requireSafe(siteId) + "|" + requireSafe(slug), path);
        return this;
    }

    /** What the next {@link #install} of a new page answers (default {@code PUBLISHED}). */
    public FakeSiteServices nextInstall(InstallResult.Outcome outcome, Finding... blocking) {
        this.nextOutcome = Objects.requireNonNull(outcome, "outcome");
        this.nextBlocking = List.of(blocking);
        return this;
    }

    /** Every starter page {@link #install} was asked to install, in order. */
    public List<StarterPage> installs() {
        return List.copyOf(installs);
    }

    @Override
    public Optional<SiteView> find(String siteId) {
        return Optional.ofNullable(sites.get(requireSafe(siteId)));
    }

    @Override
    public Optional<SiteView> site() {
        String id = current;
        return id == null ? Optional.empty() : Optional.ofNullable(sites.get(id));
    }

    @Override
    public boolean may(String permissionKey) {
        return permissionKey != null && granted.contains(permissionKey);
    }

    @Override
    public String site(String siteId, Locale locale, String code, String fallback, Object... args) {
        requireSafe(siteId);
        return format(messages.get(siteId + "|" + code), code, fallback, locale, args);
    }

    @Override
    public String console(String code, String fallback, Object... args) {
        return format(messages.get("|" + code), code, fallback, Locale.ENGLISH, args);
    }

    @Override
    public Optional<String> pathOf(String siteId, String slug) {
        return Optional.ofNullable(pages.get(requireSafe(siteId) + "|" + requireSafe(slug)));
    }

    @Override
    public InstallResult install(String siteId, StarterPage page) {
        Objects.requireNonNull(page, "page");
        installs.add(page);
        Optional<String> existing = pathOf(siteId, page.slug());
        if (existing.isPresent()) {
            return new InstallResult(InstallResult.Outcome.ALREADY_EXISTS, existing.get(), List.of());
        }
        if (!page.path().startsWith("/") || page.path().startsWith("/__")) {
            throw new IllegalArgumentException("unsafe or reserved page path: " + page.path());
        }
        pages.put(siteId + "|" + page.slug(), page.path());
        InstallResult.Outcome outcome = nextOutcome;
        return new InstallResult(outcome, page.path(),
                outcome == InstallResult.Outcome.DRAFT_BLOCKED ? new ArrayList<>(nextBlocking) : List.of());
    }

    private static String format(String found, String code, String fallback, Locale locale, Object[] args) {
        String text = found != null ? found : fallback != null ? fallback : code;
        return (args == null || args.length == 0) ? text : new MessageFormat(text, locale).format(args);
    }

    private static String requireSafe(String id) {
        if (id == null || id.equals(".") || id.equals("..") || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("unsafe identifier: " + id);
        }
        return id;
    }
}
