package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.page.Page;

/**
 * The page-editor's form-backing command. Spring binds the scalar fields plus the per-locale maps (form
 * inputs named {@code title[en]}, {@code description[es]}, {@code localizedPath[fr]}) and the multi-valued
 * {@code locales} checkboxes. {@link #toPage(String, boolean)} assembles a {@link Page} from it, dropping
 * blank per-locale entries (an empty title input must not persist an empty title) — the {@code Page}
 * constructor then sanitizes the localized paths and normalizes the rest.
 *
 * <p>The {@code draft} flag is <b>not</b> a form field: editing content is {@code EDIT_PAGES}, but changing a
 * page's published/draft visibility is {@code DISABLE_PAGES} (the disable/enable actions), so a save
 * preserves the page's current draft state — the caller passes it in.
 *
 * <p>Per-page template {@code params} bind as two <b>parallel arrays</b> ({@code paramKey[]} /
 * {@code paramValue[]}) rather than an indexed map: the key of a param row is itself editable (unlike the
 * fixed {@code title[en]} locale keys), so a fixed-key input name could not carry a renamed or newly-added
 * key. {@link #toPage(String, boolean)} zips the two lists positionally, trims keys, drops blank-keyed rows,
 * and keeps the last value on a duplicate key.
 */
public class PageForm {

    private String path = "";
    private String template = "";
    private String activeNav = "";
    private String author = "";
    private String publishedAt = "";
    private String canonical = "";
    private String cacheControl = "";
    private boolean noindex;
    private boolean nosnippet;
    private boolean gone;
    private String html = "";
    private String thxml = "";
    private Map<String, String> title = new LinkedHashMap<>();
    private Map<String, String> description = new LinkedHashMap<>();
    private Map<String, String> localizedPath = new LinkedHashMap<>();
    private List<String> locales = new ArrayList<>();
    private List<String> paramKey = new ArrayList<>();
    private List<String> paramValue = new ArrayList<>();

    /** Builds the {@link Page} to persist: the given slug + draft state, this form's metadata, blanks dropped. */
    public Page toPage(String slug, boolean draft) {
        String cleanPath = (path == null || path.isBlank()) ? null : path.trim();
        String cleanTemplate = (template == null || template.isBlank()) ? null : template.trim();
        List<Locale> localeList = locales.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(Locale::forLanguageTag)
                .toList();
        // The Page constructor normalizes a blank canonical / cacheControl to null (no override); noindex is a
        // plain flag (an unchecked checkbox binds to false — the site default: indexable). It also sanitizes
        // the zipped params map (trims keys, drops blank keys), so this only needs to pair the two arrays.
        return new Page(slug, cleanPath, nonBlank(localizedPath), localeList,
                nonBlank(title), nonBlank(description),
                activeNav == null ? "" : activeNav.trim(), cleanTemplate, draft, noindex, canonical, cacheControl,
                zipParams(), author, parsePublishedAt(), nosnippet, gone);
    }

    /**
     * Parses the {@code publishedAt} field (an HTML {@code <input type="date">}, so {@code yyyy-MM-dd}) into an
     * instant at <b>midnight UTC</b> on that day.
     *
     * <p>A date rather than a timestamp because publication date is an editorial fact at day granularity — what
     * a byline shows and what {@code datePublished} means to a consumer. Fixing a typo at 4pm must not move it.
     *
     * <p>An unparseable value yields {@code null} (no date) rather than throwing: this field is optional
     * metadata, and a malformed date must never block saving a page's content. A wrong-but-parseable date is the
     * author's to correct; an absent one asserts nothing, which is the safe failure.
     */
    private java.time.Instant parsePublishedAt() {
        if (publishedAt == null || publishedAt.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(publishedAt.trim())
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Pairs the {@code paramKey[]} / {@code paramValue[]} arrays positionally into a map: a row with a
     * blank/null key is dropped (its value cannot be addressed), a missing value at an index becomes empty,
     * and the last value wins on a duplicate key. The {@link Page} constructor trims and re-validates keys,
     * so this stays deliberately thin.
     */
    private Map<String, String> zipParams() {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < paramKey.size(); i++) {
            String key = paramKey.get(i);
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = i < paramValue.size() && paramValue.get(i) != null ? paramValue.get(i) : "";
            params.put(key.trim(), value);
        }
        return params;
    }

    /** A copy of {@code raw} with blank (or null) values removed, keys trimmed of empties. */
    private static Map<String, String> nonBlank(Map<String, String> raw) {
        Map<String, String> clean = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) {
                clean.put(key, value.trim());
            }
        });
        return clean;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public String getActiveNav() {
        return activeNav;
    }

    public void setActiveNav(String activeNav) {
        this.activeNav = activeNav;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(String publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getCanonical() {
        return canonical;
    }

    public void setCanonical(String canonical) {
        this.canonical = canonical;
    }

    public String getCacheControl() {
        return cacheControl;
    }

    public void setCacheControl(String cacheControl) {
        this.cacheControl = cacheControl;
    }

    public boolean isNosnippet() {
        return nosnippet;
    }

    public void setNosnippet(boolean nosnippet) {
        this.nosnippet = nosnippet;
    }

    public boolean isGone() {
        return gone;
    }

    public void setGone(boolean gone) {
        this.gone = gone;
    }

    public boolean isNoindex() {
        return noindex;
    }

    public void setNoindex(boolean noindex) {
        this.noindex = noindex;
    }

    public String getHtml() {
        return html;
    }

    public void setHtml(String html) {
        this.html = html;
    }

    public String getThxml() {
        return thxml;
    }

    public void setThxml(String thxml) {
        this.thxml = thxml;
    }

    public Map<String, String> getTitle() {
        return title;
    }

    public void setTitle(Map<String, String> title) {
        this.title = title;
    }

    public Map<String, String> getDescription() {
        return description;
    }

    public void setDescription(Map<String, String> description) {
        this.description = description;
    }

    public Map<String, String> getLocalizedPath() {
        return localizedPath;
    }

    public void setLocalizedPath(Map<String, String> localizedPath) {
        this.localizedPath = localizedPath;
    }

    public List<String> getLocales() {
        return locales;
    }

    public void setLocales(List<String> locales) {
        this.locales = locales;
    }

    public List<String> getParamKey() {
        return paramKey;
    }

    public void setParamKey(List<String> paramKey) {
        this.paramKey = paramKey;
    }

    public List<String> getParamValue() {
        return paramValue;
    }

    public void setParamValue(List<String> paramValue) {
        this.paramValue = paramValue;
    }
}
