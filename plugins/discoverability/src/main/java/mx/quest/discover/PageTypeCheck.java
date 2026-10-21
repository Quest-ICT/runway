package mx.quest.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * The page editor's line about structured data: what this page is described as, and where that comes from — the
 * site's Default page type, or a Per-page types ({@code content.schema}) pattern — plus what the entry adds, and a
 * warning for a type the plugin does not know, a second page type it ignores, or a block that publishes nothing of
 * its own (an {@code FAQPage} with no complete question). A noindex page carries no structured data, and the line
 * says so first, as the head checks it first. A translated page that the default language's entry does not reach
 * gets a note naming the key that would.
 *
 * <h2>Why</h2>
 * The site form says "Default page type", and nothing showed whether a
 * given page took it or something else. The answer is computed by {@link PageTypes}, the same code the public page's
 * {@code <head>} uses, so the editor cannot describe a page differently from how it is served.
 *
 * <p>Reported through the existing quality-check seam as {@code INFO} (a neutral observation, never a gate) and
 * {@code WARNING} findings; their text is resolved here in the operator's UI language, since a finding carries no
 * message arguments.
 */
public class PageTypeCheck implements PageQualityCheck {

    static final String CODE = "discoverability.page-type";
    static final String CODE_UNKNOWN = "discoverability.page-type.unknown";
    static final String CODE_EXTRA = "discoverability.page-type.extra";
    static final String CODE_GAP = "discoverability.page-type.gap";
    static final String CODE_OTHER_LANGUAGE = "discoverability.page-type.other-language";

    private final PluginSettings settings;
    private final Map<String, SchemaType> types;
    private final Messages messages;

    PageTypeCheck(PluginSettings settings, Map<String, SchemaType> types, Messages messages) {
        this.settings = settings;
        this.types = types;
        this.messages = messages;
    }

    @Override
    public String key() {
        return "discoverability/page-type";
    }

    @Override
    public List<Finding> inspect(PageView page) {
        if (page == null || page.canonical() == null || page.canonical().isBlank()) {
            return List.of();
        }
        if (page.page() != null && page.page().noindex()) {
            // The head emits no JSON-LD on a noindex page, before it looks at anything else; so does this line.
            return List.of(Finding.info(CODE, text("discover.pagetype.noindex",
                    "Structured data: this page is excluded from search engines (noindex), so it carries no "
                            + "structured data.")));
        }
        Map<String, Object> cfg = settings.site(page.site().id()).values();
        if (!SiteConfig.bool(cfg, "jsonld.enabled", true)) {
            return List.of(Finding.info(CODE, text("discover.pagetype.off",
                    "Structured data is switched off for this site, so this page is not described to search "
                            + "engines.")));
        }
        String path = PageTypes.pathOf(page);
        PageTypes.Description d = PageTypes.describe(cfg, path, types);
        List<Finding> findings = new ArrayList<>();
        String main = d.source() == PageTypes.Source.SCHEMA
                ? text("discover.pagetype.schema", "Structured data: this page is a {0}, set by Per-page types "
                        + "(pattern {1}).", d.type(), d.pattern())
                : text("discover.pagetype.default", "Structured data: this page is a {0}, the site''s Default "
                        + "page type.", d.type());
        if (!d.added().isEmpty()) {
            main = main + " " + text("discover.pagetype.added", "Per-page types also add: {0}.",
                    String.join(", ", d.addedTypes()));
        }
        findings.add(Finding.info(CODE, main));
        PageTypes.OtherLanguageEntry other = PageTypes.otherLanguageEntry(cfg, path, page.site());
        if (other != null) {
            findings.add(Finding.info(CODE_OTHER_LANGUAGE, text("discover.pagetype.otherLanguage",
                    "Per-page types: {0} describes the default language''s page, not this one; a page in another "
                            + "language is matched only by its own path. To describe this page, add {1}.",
                    other.pattern(), other.suggestion())));
        }
        if (d.pageBlock() != null) {
            gap(findings, d.type(), d.pageBlock());
        }
        for (Map<String, Object> block : d.added()) {
            gap(findings, Schemas.str(block, "type", ""), block);
        }
        if (!d.unknown().isEmpty()) {
            findings.add(Finding.warning(CODE_UNKNOWN, text("discover.pagetype.unknown",
                    "Per-page types name {0} for this page, which this plugin does not know; it is ignored.",
                    String.join(", ", d.unknown()))));
        }
        if (!d.extraPageTypes().isEmpty()) {
            findings.add(Finding.warning(CODE_EXTRA, text("discover.pagetype.extra",
                    "Per-page types give this page more than one page type; only the first ({0}) applies, "
                            + "{1} is ignored.", d.type(), String.join(", ", d.extraPageTypes()))));
        }
        return findings;
    }

    /** A warning for a block that applies but publishes nothing of its own ({@link SchemaType#gap}). */
    private void gap(List<Finding> findings, String token, Map<String, Object> block) {
        SchemaType handler = types.get(token);
        SchemaType.Gap gap = handler == null ? null : handler.gap(token, block);
        if (gap != null) {
            findings.add(Finding.warning(CODE_GAP, text(gap.code(), gap.fallback(), token)));
        }
    }

    private String text(String code, String fallback, Object... args) {
        return messages == null ? java.text.MessageFormat.format(fallback, args)
                : messages.console(code, fallback, args);
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("quality.advisory", "quality.structured-data");
    }
}
