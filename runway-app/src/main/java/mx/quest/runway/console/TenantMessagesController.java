package mx.quest.runway.console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.content.ContentChangedEvent;
import mx.quest.runway.content.PagesContentSource;
import mx.quest.runway.i18n.SiteAwareMessageSource;
import mx.quest.runway.i18n.SiteMessagesStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's <b>Messages</b> screen ({@code /__admin/messages}): a site manager edits the site's own
 * i18n message bundles — the translated strings its templates resolve with {@code #{}}. The read side already
 * exists ({@link SiteAwareMessageSource} over {@code sites/<id>/i18n/messages[_<locale>].properties}); this is the
 * missing write side, so a manager can localize their site without touching files on disk.
 *
 * <h2>UX</h2>
 * The editor is a <b>key × locale</b> grid: one row per message key, one editable value per the site's available
 * locales ({@link Site#locales()}, default first). A manager can change any value, <b>add</b> a new key (with a
 * value per locale), and <b>remove</b> a key (unticking its "keep" box drops it from every locale on save). A
 * blank value means "this locale has no value for this key" — the key is omitted from that locale's file
 * (Runway has no app-level fallback, so an unfilled locale renders {@code ??key??}, a visible prompt to translate
 * it).
 *
 * <h2>Immediate apply</h2>
 * A save writes each locale's bundle via {@link SiteMessagesStore} and then <b>evicts</b> the site's cached
 * message source ({@link SiteAwareMessageSource#evictSite}), the exact eviction the read side listens for — so a
 * changed string is live on the very next public render, no restart.
 *
 * <h2>Two save paths</h2>
 * {@link #apply} is the primary path: the "Save changes" button posts <b>only the changed cells</b> as JSON, so
 * editing one message is a tiny request, not the whole grid ({@link SiteMessagesStore#merge}). {@link #save} is
 * the full-grid form POST retained as the <b>no-JavaScript fallback</b> — with scripting on, the page intercepts
 * the form submit and calls {@link #apply} instead. Both share the same isolation, {@code CONFIGURE_SITE} gate,
 * store, and cache eviction.
 *
 * <h2>Isolation</h2>
 * The edited tenancy is <em>always</em> the bound one ({@link SiteResolver#current()}), never a path/param id — so
 * this screen can only read and write the current site's own {@code i18n/} folder, and a second site is provably
 * untouched. Gated by the grantable {@code CONFIGURE_SITE} permission (the same gate as SEO/Icons/Config).
 */
@Controller
public class TenantMessagesController {

    /**
     * Upper bound on the edits ({@code cells + removedKeys}) one incremental save may carry — a DoS cap mirroring
     * the {@code maxFormKeys} bound the full-grid form path enforces, so the JSON {@link #apply} path is not an
     * unbounded-work hole. Generous enough for a whole-site translation pass (tens of thousands of cells) yet
     * bounded; the endpoint is also {@code CONFIGURE_SITE}-gated behind per-site CSRF.
     */
    static final int MAX_EDITS_PER_REQUEST = 100_000;

    private static final Logger log = LoggerFactory.getLogger(TenantMessagesController.class);

    private final SiteResolver siteResolver;
    private final SiteMessagesStore store;
    private final SiteAwareMessageSource siteMessages;
    private final MessageSource messages;
    private final ApplicationEventPublisher events;

    public TenantMessagesController(SiteResolver siteResolver, SiteMessagesStore store,
                                    SiteAwareMessageSource siteMessages,
                                    @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                    ApplicationEventPublisher events) {
        this.siteResolver = siteResolver;
        this.store = store;
        this.siteMessages = siteMessages;
        this.messages = messages;
        this.events = events;
    }

    /**
     * After a save: the read side re-reads the bundle, and every page's rendered text may have changed (a page's
     * visible copy can come from the bundle through decoupled logic), so the pages source announces a change to
     * its whole corpus — the search index rebuilds from the new strings.
     */
    private void messagesChanged(String siteId) {
        siteMessages.evictSite(siteId); // read side re-reads the bundle → change is live next render
        try {
            events.publishEvent(new ContentChangedEvent(siteId, PagesContentSource.KEY, null));
        } catch (RuntimeException e) {
            // The save itself succeeded; an index that missed the change catches up at its freshness backstop.
            log.warn("messages of site '{}' saved, but the change announcement failed", siteId, e);
        }
    }

    /** One editor row: the message key and, in the site's locale order, its per-locale values. */
    public record MessageRow(String key, List<String> values) {
    }

    /** One edited cell in an incremental save: a value for a single {@code key} in a single {@code locale}. */
    public record Cell(String key, String locale, String value) {
    }

    /**
     * The payload of an incremental save (the "Save changes" button posts only what changed): the edited
     * {@code cells} (upserts — a blank value clears that one locale) and the {@code removedKeys} (keys deleted from
     * every locale). Either list may be null/empty.
     */
    public record MessageEdits(List<Cell> cells, List<String> removedKeys) {
    }

    @GetMapping("/__admin/messages")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String form(Model model) {
        Site site = boundTenancy();
        List<Locale> locales = site.locales();
        model.addAttribute("localeTags", locales.stream().map(Locale::toLanguageTag).toList());
        model.addAttribute("defaultLocaleTag", site.defaultLocale().toLanguageTag());
        model.addAttribute("rows", rows(store.load(site.id(), locales), locales));
        model.addAttribute("localeCount", locales.size());
        model.addAttribute("pageTitleKey", "op.messages.title");
        model.addAttribute("contentTemplate", "operator/admin/messages");
        model.addAttribute("contentScripts", "operator/admin/messages");
        return "operator/op-layout";
    }

    /**
     * Save the grid. Each existing row posts a hidden {@code key} and, per locale, a {@code value} in the site's
     * locale order; a row is kept only if its {@code keep} flag is present (so unticking removes the key). A
     * trailing add-row posts {@code newKey} + its per-locale {@code newValue} list. The store then rewrites only
     * the bound site's bundles and the cache is evicted.
     */
    @PostMapping("/__admin/messages")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String save(@RequestParam(name = "key", required = false) List<String> keys,
                       @RequestParam(name = "keep", required = false) List<String> keptKeys,
                       @RequestParam(name = "newKey", required = false) String newKey,
                       @RequestParam MultiValueMap<String, String> allParams,
                       RedirectAttributes redirect) {
        Site site = boundTenancy();
        List<Locale> locales = site.locales();

        // Build key → (localeTag → value). Duplicate keys collapse (last wins); a removed (un-kept) key is skipped
        // entirely so it drops from every locale file. The per-locale value lists are positional, aligned to keys.
        Map<String, Map<String, String>> grid = new LinkedHashMap<>();
        java.util.Set<String> keep = keptKeys == null ? java.util.Set.of() : new java.util.HashSet<>(keptKeys);
        if (keys != null) {
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i) == null ? "" : keys.get(i).strip();
                if (key.isEmpty() || !keep.contains(keys.get(i))) {
                    continue; // blank key, or the row was un-kept (remove) → do not carry it forward
                }
                grid.put(key, rowValues(allParams, "value", locales, i));
            }
        }
        // The add-row: a new key with its own per-locale values (positioned at index 0 of the newValue lists).
        // If the key is ALREADY a kept row, MERGE the add-row's non-blank values over it rather than replacing the
        // whole row — otherwise a locale left blank in the add card would silently drop that language from the
        // existing key. This matches the incremental (merge) path, so JS-on and no-JS produce the same on-disk state.
        if (newKey != null && !newKey.isBlank()) {
            String nk = newKey.strip();
            Map<String, String> addValues = rowValues(allParams, "newValue", locales, 0);
            Map<String, String> existing = grid.get(nk);
            if (existing != null) {
                addValues.forEach((tag, val) -> {
                    if (val != null && !val.isBlank()) {
                        existing.put(tag, val);
                    }
                });
            } else {
                grid.put(nk, addValues);
            }
        }

        try {
            store.save(site.id(), locales, grid);
            messagesChanged(site.id());
            redirect.addFlashAttribute("flash", message("op.messages.saved", "Messages saved."));
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", message("op.messages.failed", "Could not save the messages."));
        }
        return "redirect:/__admin/messages";
    }

    /**
     * Incremental save: applies <b>only the changed cells</b> (and any fully-removed keys) instead of rewriting the
     * whole grid. This is what the editor's "Save changes" button posts as JSON — a one-message edit is a tiny
     * request rather than the site's entire bundle. The full-grid {@link #save} above remains as the no-JavaScript
     * fallback.
     *
     * <p>Isolation and safety are identical to the full save: the tenancy is the bound one
     * ({@link SiteResolver#current()}, never a param), the {@code CONFIGURE_SITE} gate applies, and every posted
     * locale tag is <b>allowlisted against the site's own locales</b> (matched by {@code toLanguageTag} string) so a
     * client can never coin a {@code messages_&lt;tag&gt;.properties} filename the site does not offer — an unknown
     * tag fails closed with {@code 400}. Keys are never used as a path (they are escaped into the file body), so
     * they need no traversal check. On success the read cache is evicted, exactly as the full save does.
     *
     * @param edits the cells to upsert and the keys to remove (see {@link MessageEdits})
     * @return {@code 200 {"saved": n}} on success, {@code 400} for an unknown locale, {@code 500} on write failure
     */
    @PostMapping(value = "/__admin/messages/apply", consumes = "application/json")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apply(@RequestBody MessageEdits edits) {
        Site site = boundTenancy();
        List<Locale> locales = site.locales();

        int requested = (edits.cells() == null ? 0 : edits.cells().size())
                + (edits.removedKeys() == null ? 0 : edits.removedKeys().size());
        if (requested > MAX_EDITS_PER_REQUEST) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "error", message("op.messages.failed", "Could not save the messages.")));
        }

        Set<String> allowedTags = new java.util.HashSet<>();
        for (Locale locale : locales) {
            allowedTags.add(locale.toLanguageTag());
        }

        // Group cells into localeTag → (key → value). Reject any locale the site does not offer (fail closed) so a
        // posted tag can never become a bundle filename outside the site's own languages.
        Map<String, Map<String, String>> upserts = new LinkedHashMap<>();
        if (edits.cells() != null) {
            for (Cell cell : edits.cells()) {
                if (cell == null || cell.key() == null || cell.locale() == null) {
                    continue;
                }
                String key = cell.key().strip();
                if (key.isEmpty()) {
                    continue;
                }
                if (!allowedTags.contains(cell.locale())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", message("op.messages.failed", "Could not save the messages."),
                            "locale", cell.locale()));
                }
                upserts.computeIfAbsent(cell.locale(), t -> new LinkedHashMap<>())
                        .put(key, cell.value() == null ? "" : cell.value());
            }
        }
        Set<String> removed = new LinkedHashSet<>();
        if (edits.removedKeys() != null) {
            for (String k : edits.removedKeys()) {
                if (k != null && !k.strip().isEmpty()) {
                    removed.add(k.strip());
                }
            }
        }

        int saved = upserts.values().stream().mapToInt(Map::size).sum() + removed.size();
        if (saved == 0) {
            return ResponseEntity.ok(Map.of("saved", 0)); // nothing changed → no write, no eviction
        }
        try {
            store.merge(site.id(), locales, upserts, removed);
            messagesChanged(site.id());
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", message("op.messages.failed", "Could not save the messages.")));
        }
        return ResponseEntity.ok(Map.of("saved", saved));
    }

    /**
     * Pulls one row's per-locale values out of the flat param map. Each locale has its own indexed list
     * ({@code <prefix>_<localeTag>}), so value {@code i} of the {@code en} list pairs with key {@code i}. A missing
     * entry becomes the empty string (the store reads that as "no value for this locale").
     */
    private static Map<String, String> rowValues(MultiValueMap<String, String> params, String prefix,
                                                  List<Locale> locales, int index) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Locale locale : locales) {
            String tag = locale.toLanguageTag();
            List<String> list = params.get(prefix + "_" + tag);
            String value = (list != null && index < list.size() && list.get(index) != null) ? list.get(index) : "";
            values.put(tag, value);
        }
        return values;
    }

    /** Turns the store's key→(tag→value) grid into ordered rows whose value list follows the site's locale order. */
    private static List<MessageRow> rows(Map<String, Map<String, String>> grid, List<Locale> locales) {
        List<MessageRow> rows = new ArrayList<>(grid.size());
        grid.forEach((key, row) -> {
            List<String> values = new ArrayList<>(locales.size());
            for (Locale locale : locales) {
                values.add(row.getOrDefault(locale.toLanguageTag(), ""));
            }
            rows.add(new MessageRow(key, values));
        });
        return rows;
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
