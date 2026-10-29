package mx.quest.demodatabase;

import mx.quest.database.spi.DatabaseException;
import mx.quest.database.spi.PluginDatabase;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The site console's guestbook screen: the entries a page at a time, and a delete button for each. Gated by
 * {@code runway-demo-database|manage}; the site is always the one the console manages ({@code currentSite()}), and
 * deleting is a CSRF-checked POST.
 */
@Controller
@ConsoleScreen
@RequestMapping(GuestbookConsoleController.BASE)
public class GuestbookConsoleController {

    static final String BASE = "/__admin/runway-demo-database";
    static final String GATE = "@authz.may('runway-demo-database|manage')";
    static final String FLASH = "guestbookFlash";
    static final String FLASH_ERROR = "guestbookError";

    private static final Logger log = LoggerFactory.getLogger(GuestbookConsoleController.class);

    /** What the screen shows. */
    public record Screen(boolean available, GuestbookStore.Page page, int count) {
    }

    private final GuestbookStore store;
    private final Messages messages;

    public GuestbookConsoleController(GuestbookStore store, Messages messages) {
        this.store = store;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(GATE)
    public String list(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        PluginDatabase db = store.db();
        Screen screen = new Screen(false, new GuestbookStore.Page(java.util.List.of(), 0, false), 0);
        if (db.available()) {
            try {
                screen = new Screen(true, store.page(db.currentSite(), Math.max(0, page), 50),
                        store.count(db.currentSite()));
            } catch (DatabaseException e) {
                log.warn("runway-demo-database: the guestbook could not be read: {}", e.getMessage());
            }
        }
        model.addAttribute("gb", screen);
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/runway-demo-database/console");
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "guestbook.console.title");
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @PostMapping("/delete")
    @PreAuthorize(GATE)
    public String delete(@RequestParam(name = "id") long id, RedirectAttributes flash) {
        PluginDatabase db = store.db();
        try {
            boolean deleted = db.available() && store.delete(db.currentSite(), id);
            flash.addFlashAttribute(deleted ? FLASH : FLASH_ERROR, deleted
                    ? messages.console("guestbook.console.deleted", "Entry deleted.")
                    : messages.console("guestbook.console.gone", "That entry is no longer there."));
        } catch (DatabaseException e) {
            log.warn("runway-demo-database: a guestbook entry could not be deleted: {}", e.getMessage());
            flash.addFlashAttribute(FLASH_ERROR, messages.console("guestbook.console.failed",
                    "The database could not be reached."));
        }
        return "redirect:" + BASE;
    }
}
