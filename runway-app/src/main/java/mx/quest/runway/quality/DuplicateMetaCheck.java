package mx.quest.runway.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.springframework.stereotype.Component;

/**
 * The same title or description as another page of the site.
 *
 * <ul>
 *   <li>{@code title.duplicate} — WARNING.</li>
 *   <li>{@code description.duplicate} — WARNING.</li>
 * </ul>
 *
 * <p>Two pages that announce themselves identically compete with each other in search results and look like
 * duplicates to an engine. That weakens the site but breaks nothing, so neither finding blocks publishing.
 *
 * <p>Compared against the site's other <b>published</b> pages that are offered in the inspected locale (a draft
 * is nobody's competitor yet), case- and whitespace-insensitively, using each page's own title/description for
 * that locale. The inspected page itself is identified by its slug, so a pending revision or an unsaved edit of a
 * page is never reported as a duplicate of its own live version. Content-source pages (a blog entry) are not
 * compared — they are not kernel pages and carry their own metadata.
 *
 * <p>This is the one kernel check that reads beyond its {@link PageView}: the SPI view describes one page, and a
 * duplicate is a fact about the site. It reads the kernel's (cached) page list, which is why it lives in the
 * kernel rather than being expressible as a plugin check over the SPI alone.
 */
@Component
public class DuplicateMetaCheck implements PageQualityCheck {

    private final PageStore pages;

    public DuplicateMetaCheck(PageStore pages) {
        this.pages = pages;
    }

    @Override
    public String key() {
        return "core.duplicates";
    }

    @Override
    public List<Finding> inspect(PageView view) {
        if (view.page().isVirtual()) {
            return List.of();
        }
        Locale locale = view.locale();
        Locale siteDefault = view.site().defaultLocale();
        List<Page> others = pages.findAll(view.site().id()).stream()
                .filter(Page::published)
                .filter(p -> !p.slug().equals(view.page().slug()))
                .filter(p -> p.hasLocale(locale, siteDefault))
                .toList();
        List<Finding> findings = new ArrayList<>(2);
        duplicate(view.page().title(), others, Page::titleFor, locale).ifPresent(slugs ->
                findings.add(Finding.warning("title.duplicate", "the title is also used by: " + slugs)));
        duplicate(view.page().description(), others, Page::descriptionFor, locale).ifPresent(slugs ->
                findings.add(Finding.warning("description.duplicate", "the description is also used by: " + slugs)));
        return findings;
    }

    /** The comma-joined slugs of {@code others} whose value equals {@code mine} (normalized), if any. */
    private static java.util.Optional<String> duplicate(String mine, List<Page> others,
                                                        BiFunction<Page, Locale, String> value, Locale locale) {
        if (mine == null || mine.isBlank()) {
            return java.util.Optional.empty(); // an absent value is the presence checks' business, not a duplicate
        }
        String key = key(mine);
        List<String> clashes = others.stream()
                .filter(p -> key.equals(key(value.apply(p, locale))))
                .map(Page::slug)
                .toList();
        return clashes.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(String.join(", ", clashes));
    }

    private static String key(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
