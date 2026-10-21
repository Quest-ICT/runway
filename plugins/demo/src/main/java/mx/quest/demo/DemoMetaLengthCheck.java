package mx.quest.demo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * {@code PageQualityCheck} — read-only SEO checks over a page as it would render. Tuned per site through
 * {@link PluginSettings}: {@code quality.title-warn-ratio} (a {@code decimal}) scales the 60-character title threshold;
 * {@code quality.ignore-codes} (a {@code list}) suppresses finding codes. Returns an empty list (never {@code null})
 * for a clean page.
 *
 * <p>Severity is a decision with consequences: on a site set to <i>Enforced</i> or <i>Reviewed</i> any {@code ERROR}
 * refuses publishing. This demo's {@code h1.missing} is an {@code ERROR} on purpose, to show that path; a real plugin
 * keeps {@code ERROR} for something clearly broken and uses {@code WARNING} for advice.
 */
public class DemoMetaLengthCheck implements PageQualityCheck {

    private static final int BASE_TITLE_LIMIT = 60;

    private final PluginSettings settings;

    public DemoMetaLengthCheck(PluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String key() {
        return "runway-demo/meta-length";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        SettingsView site = settings.site(page.site().id());
        double ratio = site.decimal("quality.title-warn-ratio", BigDecimal.ONE).doubleValue();
        int titleLimit = (int) Math.max(1, Math.round(BASE_TITLE_LIMIT * ratio));
        List<String> ignore = site.list("quality.ignore-codes");

        List<Finding> findings = new ArrayList<>();
        String title = page.page().title();
        if (title != null && title.length() > titleLimit) {
            findings.add(Finding.warning("title.too-long",
                    "Title is " + title.length() + " chars; keep it under " + titleLimit + " for search results."));
        }
        if (page.page().description() == null || page.page().description().isBlank()) {
            findings.add(Finding.info("description.missing",
                    "This page has no meta description; add one for better search snippets."));
        }
        if (!hasH1(page.resolvedHtml())) {
            findings.add(Finding.error("h1.missing", "The rendered page has no <h1> heading."));
        }
        return findings.stream().filter(f -> !ignore.contains(f.code())).toList();
    }

    private static boolean hasH1(String html) {
        return html != null && html.toLowerCase(Locale.ROOT).contains("<h1");
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("quality.seo");
    }
}
