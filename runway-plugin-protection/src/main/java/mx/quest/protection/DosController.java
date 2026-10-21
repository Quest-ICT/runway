package mx.quest.protection;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.web.ClientIp;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The application-realm <b>DoS activity</b> screen ({@code /__app/dos}) — the operator's live view of the abuse
 * floor: the busiest <b>rate-limit offenders</b> (IP + recent-429 count, from the {@link TokenBucketRateLimiter})
 * and the current {@link IpBlocklist} (each entry's IP, reason, manual/auto source and expiry), with an
 * <b>Unblock</b> action per entry and a small form to <b>Block</b> an IP/CIDR manually.
 *
 * <p>App-level platform concern — never a per-tenant surface. Gated by the plugin's own {@code protection|settings}
 * control point (app realm; the app master/wildcard admin always passes), exactly like the app Protection screen.
 * Renders inside the kernel {@code op-layout} shell as a {@link ConsoleScreen} (the SPI's shell seam), supplying only
 * its {@code contentTemplate} + {@code pageTitleKey}.
 *
 * <p><b>Safety rail.</b> A manual block refuses the operator's own gated IP and any allowlisted/loopback address
 * (rejected with a clear message) — {@link IpBlocklist#block} enforces the allowlist rule too, so the screen can
 * never lock the operator out of the console. A manual block persists; an unblock removes it.
 */
@Controller
@ConsoleScreen
public class DosController {

    /** Displayed-timestamp format for a block's expiry (local zone), matching the listing-table date convention. */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final TokenBucketRateLimiter limiter;
    private final IpBlocklist blocklist;
    private final MessageSource messages;

    public DosController(TokenBucketRateLimiter limiter, IpBlocklist blocklist,
                         @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.limiter = limiter;
        this.blocklist = blocklist;
        this.messages = messages;
    }

    @GetMapping("/__app/dos")
    @PreAuthorize("@authz.may('protection|settings')")
    public String view(Model model) {
        List<OffenderRow> offenders = new ArrayList<>();
        for (TokenBucketRateLimiter.Offender o : limiter.topOffenders(50)) {
            offenders.add(new OffenderRow(o.ip(), o.requests(), o.throttled(), blocklist.isBlocked(o.ip())));
        }
        List<BlockRow> blocks = new ArrayList<>();
        for (IpBlocklist.Entry b : blocklist.list()) {
            String until = b.indefinite()
                    ? message("dos.block.indefinite", "indefinite")
                    : TS.format(Instant.ofEpochSecond(b.untilEpochSeconds()));
            String source = b.manual()
                    ? message("dos.source.manual", "manual")
                    : message("dos.source.auto", "auto");
            blocks.add(new BlockRow(b.ip(), b.reason() == null ? "" : b.reason(), source, until, b.manual()));
        }
        // list() prunes lapsed entries, so the history below is current only because it ran first.
        List<EndedRow> ended = new ArrayList<>();
        for (IpBlocklist.Lapsed e : blocklist.recentlyEnded()) {
            ended.add(new EndedRow(e.ip(), e.reason() == null ? "" : e.reason(),
                    e.manual() ? message("dos.source.manual", "manual") : message("dos.source.auto", "auto"),
                    TS.format(Instant.ofEpochSecond(e.endedAt()))));
        }

        model.addAttribute("offenders", offenders);
        model.addAttribute("blocks", blocks);
        model.addAttribute("ended", ended);
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/protection/app-dos");
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "dos.screen.title");
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @PostMapping("/__app/dos/block")
    @PreAuthorize("@authz.may('protection|settings')")
    public String block(@RequestParam(required = false) String ip,
                        @RequestParam(defaultValue = "900") long seconds,
                        @RequestParam(required = false) String reason,
                        HttpServletRequest request,
                        RedirectAttributes redirect) {
        String target = ip == null ? "" : ip.trim();
        String self = ClientIp.of(request);
        if (target.isBlank()) {
            redirect.addFlashAttribute("error", message("dos.block.blank", "Enter an IP address or CIDR to block."));
        } else if (target.equals(self) || blocklist.isAllowlisted(target)) {
            redirect.addFlashAttribute("error",
                    message("dos.block.refused", "Refusing to block your own address or an allowlisted one."));
        } else {
            String why = (reason == null || reason.isBlank())
                    ? message("dos.source.manual", "manual")
                    : reason.trim();
            boolean applied = blocklist.block(target, seconds, why, true);
            if (applied) {
                redirect.addFlashAttribute("flash", message("dos.block.done", "Address blocked."));
            } else {
                // block() only refuses allowlisted/blank — already handled above; this is a defensive fallback.
                redirect.addFlashAttribute("error",
                        message("dos.block.refused", "Refusing to block your own address or an allowlisted one."));
            }
        }
        return "redirect:/__app/dos";
    }

    @PostMapping("/__app/dos/unblock")
    @PreAuthorize("@authz.may('protection|settings')")
    public String unblock(@RequestParam(required = false) String ip, RedirectAttributes redirect) {
        if (ip != null && !ip.isBlank()) {
            blocklist.unblock(ip.trim());
            redirect.addFlashAttribute("flash", message("dos.unblock.done", "Address unblocked."));
        }
        return "redirect:/__app/dos";
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    /** A rate-limit offender row for the screen: the IP, its total requests, its throttled (429) count, blocked?. */
    public record OffenderRow(String ip, long requests, long throttled, boolean blocked) {
    }

    /** A blocklist row for the screen: the IP/CIDR, reason, source (manual/auto), expiry text, and manual flag. */
    /**
     * A block that is no longer in force: what it was, and when it ended.
     *
     * <p>An auto-block lasts fifteen minutes by default, so an operator asking "was the guard doing anything
     * when the site was slow at three?" arrives to an empty blocklist and no way to tell. The evidence
     * deleted itself at exactly the rate that makes it hardest to catch.
     */
    public record EndedRow(String ip, String reason, String source, String endedAt) {
    }

    public record BlockRow(String ip, String reason, String source, String until, boolean manual) {
    }
}
