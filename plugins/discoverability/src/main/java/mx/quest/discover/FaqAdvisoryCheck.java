package mx.quest.discover;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * An <b>advisory</b> {@link PageQualityCheck} that closes the loop on the type-aware structured data: the plugin
 * <em>emits</em> {@code FAQPage} from {@code content.schema}, and this check <em>detects</em> when a page has an
 * on-page FAQ (a cluster of {@code <details><summary>…</summary>…</details>} disclosures) that <b>isn't</b>
 * declared in {@code content.schema} yet, and nudges the author to add it — the "detect &amp; lift" the audit
 * asked for. FAQ is the single highest-leverage GEO win, so surfacing an unmarked one is worth an INFO finding.
 *
 * <p>It runs at <b>edit/advisory time</b> (the host passes the freshly-rendered {@link PageView#resolvedHtml()}),
 * never at render, and it never edits the page — it only reports. It is <b>quiet when there's nothing to say</b>:
 * it stays silent unless the page has at least two disclosure clusters (a single {@code <details>} is usually a
 * "read more", not an FAQ), when the page is <b>already typed</b> {@code FAQPage} (the head renders it as one; no
 * nagging about a FAQ that's already marked up), and on a noindex page, which carries no structured data. The
 * finding is localized by its {@code code} via the host's message source
 * ({@code quality.discoverability.faq.unmarked}); the string here is only the English fallback.
 */
public class FaqAdvisoryCheck implements PageQualityCheck {

    /** A {@code <details>} disclosure carrying a {@code <summary>} — i.e. a question/answer cluster. */
    private static final Pattern DISCLOSURE = Pattern.compile(
            "<details\\b[^>]*>.*?<summary\\b[^>]*>.*?</summary>.*?</details>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Below this many disclosures a page is unlikely to be an FAQ (avoids flagging a lone "read more"). */
    private static final int MIN_DISCLOSURES = 2;

    private static final String FALLBACK =
            "This page has an on-page FAQ (a <details>/<summary> block) that isn't emitting FAQPage structured "
            + "data. Add it to the discoverability plugin's content.schema for this path (see the plugin help).";

    private final PluginSettings settings;
    private final Map<String, SchemaType> types;

    public FaqAdvisoryCheck(PluginSettings settings) {
        this(settings, DiscoverabilityConfig.schemaTypes());
    }

    FaqAdvisoryCheck(PluginSettings settings, Map<String, SchemaType> types) {
        this.settings = settings;
        this.types = types;
    }

    @Override
    public String key() {
        return "discoverability/faq-advisory";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        if (page == null || page.resolvedHtml().isBlank() || countDisclosures(page.resolvedHtml()) < MIN_DISCLOSURES) {
            return List.of();
        }
        if (page.page() != null && page.page().noindex()) {
            return List.of(); // a noindex page carries no structured data, so FAQPage would do nothing there
        }
        if (!SiteConfig.bool(settings.site(page.site().id()).values(), "jsonld.enabled", true)) {
            return List.of(); // structured data is off for the site, so FAQPage markup would never be published
        }
        if (alreadyTypedFaq(page)) {
            return List.of(); // already marked up as FAQPage — nothing to advise
        }
        return List.of(Finding.info("discoverability.faq.unmarked", FALLBACK));
    }

    private static int countDisclosures(String html) {
        Matcher m = DISCLOSURE.matcher(html);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    /**
     * Whether the head renders this page as an {@code FAQPage}: the same {@link PageTypes} answer the head and the
     * editor's line use. So a misspelt {@code faqpage} (an unknown type) or an {@code FAQPage} block after another
     * page type (ignored) does not count as typed, as the page served does not carry it either.
     */
    private boolean alreadyTypedFaq(PageView page) {
        String canonical = page.canonical();
        if (canonical == null || canonical.isBlank()) {
            return false; // no canonical to derive a path from — advise rather than assume it's typed
        }
        Map<String, Object> cfg = settings.site(page.site().id()).values();
        return "FAQPage".equals(PageTypes.describe(cfg, PageTypes.pathOf(page), types).type());
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("quality.advisory", "quality.geo");
    }
}
