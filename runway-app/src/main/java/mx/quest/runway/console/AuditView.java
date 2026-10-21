package mx.quest.runway.console;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.log.AuditEntry;
import mx.quest.runway.log.AuditPage;
import mx.quest.runway.log.AuditQuery;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The presentation half of the two audit-journal screens — the application Activity log ({@code /__app/logs})
 * and the per-site audit view on {@code /__admin/logs}. Both show the same thing (a {@link AuditPage} of
 * {@link AuditEntry} rows with date/actor/action filters, paging and a CSV download), so the request parsing, the
 * row shaping, the localized action labels and the CSV encoding live here once. The data side is
 * {@link mx.quest.runway.log.AuditJournal}; gating is each controller's (and its realm's security chain's) job.
 *
 * <h2>Labels</h2>
 * An action key {@code a.b} is shown as the operator message {@code op.audit.action.a.b} in the viewer's
 * language, falling back to the raw key for an action nothing declares (a plugin's, or one added later) — the raw
 * key is always shown too, because it is what the action filter matches. A legacy key carrying a suffix after
 * {@code :} (older {@code master.assigned:<site>} lines) is labelled by its part before the colon.
 *
 * <h2>CSV</h2>
 * RFC 4180 (every cell quoted, quotes doubled, CRLF rows) with a UTF-8 BOM so spreadsheet tools read accents
 * correctly. Spreadsheet-formula injection is neutralized: a cell beginning with {@code = + - @}, a tab or a CR is
 * prefixed with a single quote, so a crafted username or detail can never execute as a formula when an
 * auditor opens the export.
 */
final class AuditView {

    /** Rows per screen page. */
    static final int PAGE_SIZE = 100;
    /** The most rows a CSV download carries. */
    static final int MAX_EXPORT_ROWS = 10_000;
    /** The highest page index accepted (the scan budget ends far sooner; this only bounds the arithmetic). */
    private static final int MAX_PAGE = 10_000;
    /** Free-text filters are clipped to this length (a filter is a substring, never a pattern). */
    private static final int MAX_FILTER_LENGTH = 100;

    private static final DateTimeFormatter SHOWN_WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");
    private static final DateTimeFormatter SHOWN_LEGACY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** One row as the template binds it (every value already a display string). */
    record Row(String time, String sortKey, String actor, String action, String label, String detail) {
    }

    /** The parsed filter of one request, echoed back into the form. */
    record Filter(String from, String to, String actor, String action, int page) {

        AuditQuery query() {
            return new AuditQuery(date(from), date(to), actor, action);
        }
    }

    private AuditView() {
    }

    /**
     * Reads the request's filter parameters. A malformed date is dropped (treated as no bound) rather than
     * rejected; text filters are stripped and clipped; the page index is clamped to {@code [0, MAX_PAGE]}.
     */
    static Filter filter(String from, String to, String actor, String action, Integer page) {
        LocalDate fromDay = date(from);
        LocalDate toDay = date(to);
        if (fromDay != null && toDay != null && toDay.isBefore(fromDay)) {
            LocalDate swap = fromDay; // an inverted range is almost always a slip — read it the obvious way
            fromDay = toDay;
            toDay = swap;
        }
        int p = page == null ? 0 : Math.max(0, Math.min(MAX_PAGE, page));
        return new Filter(fromDay == null ? "" : fromDay.toString(), toDay == null ? "" : toDay.toString(),
                clip(actor), clip(action), p);
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String s = value.strip();
        return s.length() > MAX_FILTER_LENGTH ? s.substring(0, MAX_FILTER_LENGTH) : s;
    }

    /**
     * Fills the model attributes both audit templates bind: {@code auditRows}, the echoed filter
     * ({@code auditFilter}), paging links ({@code auditPrevUrl} / {@code auditNextUrl}, absent when there is no
     * such page), {@code auditCsvUrl}, {@code auditTruncated} / {@code auditScanned}, and the bound constants.
     *
     * @param basePath the screen's path (paging links)
     * @param csvPath  the CSV download's path
     * @param titleKey the operator message key of the card title
     * @param tableKey the listing-table layout key (unique per screen, so the two realms keep separate layouts)
     */
    static void populate(Model model, AuditPage page, Filter filter, String basePath, String csvPath,
                         String titleKey, String tableKey, MessageSource messages, Locale locale) {
        model.addAttribute("auditTitleKey", titleKey);
        model.addAttribute("auditTableKey", tableKey);
        model.addAttribute("auditRows", rows(page.rows(), messages, locale));
        model.addAttribute("auditFilter", filter);
        model.addAttribute("auditPageNumber", filter.page() + 1);
        model.addAttribute("auditPrevUrl", filter.page() > 0 ? url(basePath, filter, filter.page() - 1) : null);
        model.addAttribute("auditNextUrl", page.hasMore() ? url(basePath, filter, filter.page() + 1) : null);
        model.addAttribute("auditCsvUrl", url(csvPath, filter, -1));
        model.addAttribute("auditResetUrl", basePath);
        model.addAttribute("auditTruncated", page.truncated());
        model.addAttribute("auditScanned", page.scanned());
        model.addAttribute("auditPageSize", PAGE_SIZE);
        model.addAttribute("auditMaxExport", MAX_EXPORT_ROWS);
    }

    static List<Row> rows(List<AuditEntry> entries, MessageSource messages, Locale locale) {
        List<Row> rows = new ArrayList<>(entries.size());
        for (AuditEntry e : entries) {
            String shown = (e.offsetKnown() ? SHOWN_WITH_OFFSET : SHOWN_LEGACY).format(e.time());
            rows.add(new Row(shown, CSV_TIME.format(e.time()), e.actor(), e.action(),
                    label(e.action(), messages, locale), e.detail()));
        }
        return rows;
    }

    /** The localized label of an action key, or the raw key when no message declares it. */
    static String label(String action, MessageSource messages, Locale locale) {
        if (action == null || action.isBlank()) {
            return "";
        }
        int colon = action.indexOf(':');
        String base = colon > 0 ? action.substring(0, colon) : action;
        return messages.getMessage("op.audit.action." + base, null, action, locale);
    }

    /** The screen/CSV URL for {@code filter} at {@code page} ({@code -1} = no page parameter), URL-encoded. */
    private static String url(String path, Filter filter, int page) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(path);
        if (!filter.from().isEmpty()) {
            b.queryParam("from", filter.from());
        }
        if (!filter.to().isEmpty()) {
            b.queryParam("to", filter.to());
        }
        if (!filter.actor().isEmpty()) {
            b.queryParam("actor", filter.actor());
        }
        if (!filter.action().isEmpty()) {
            b.queryParam("action", filter.action());
        }
        if (page > 0) {
            b.queryParam("page", page);
        }
        return b.encode().build().toUriString();
    }

    /**
     * The CSV download of {@code page}'s rows (time ISO-8601 with offset, actor, raw action key, localized label,
     * detail). Sent {@code no-store} — an audit export must not linger in a shared cache.
     *
     * @param filename the attachment filename (ASCII, no path)
     */
    static ResponseEntity<byte[]> csv(AuditPage page, String filename, MessageSource messages, Locale locale) {
        StringBuilder out = new StringBuilder(64 + page.rows().size() * 96);
        out.append('﻿');
        appendRow(out, "time", "actor", "action", "label", "detail");
        for (AuditEntry e : page.rows()) {
            appendRow(out, CSV_TIME.format(e.time()), e.actor(), e.action(), label(e.action(), messages, locale),
                    e.detail());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        headers.setCacheControl("no-store");
        if (page.hasMore() || page.truncated()) {
            // The file holds fewer rows than match (row cap or scan budget) — say so to a scripted consumer.
            headers.add("X-Runway-Audit-Incomplete", "true");
        }
        return ResponseEntity.ok().headers(headers).body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendRow(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(csvSafe(cells[i]).replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    /** Neutralizes a cell a spreadsheet would evaluate as a formula (OWASP CSV-injection guidance). */
    static String csvSafe(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        char first = cell.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + cell;
        }
        return cell;
    }
}
