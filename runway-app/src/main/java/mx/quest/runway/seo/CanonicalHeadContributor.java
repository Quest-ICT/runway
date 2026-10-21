package mx.quest.runway.seo;

import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.HeadContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The kernel's canonical / robots / {@code hreflang} {@link HeadContributor} — the "for free by the kernel"
 * technical-SEO markup {@link SiteSeoConfig} refers to. It emits, from the host-resolved {@link HeadContext}
 * alone:
 *
 * <ul>
 *   <li>{@code <link rel="canonical">} — the resolved canonical URL, which honors a page's explicit
 *       {@link mx.quest.runway.page.Page#canonical() canonical override} (the host folded that in when it
 *       built the context, so this contributor stays a pure function of its input);</li>
 *   <li>{@code <meta name="robots" content="noindex, follow">} — only when the render is
 *       {@linkplain HeadContext#noindex() non-indexable} (the page opted out, or this is a preview);</li>
 *   <li>one {@code <link rel="alternate" hreflang="…">} per available locale, plus an {@code x-default}
 *       pointing at the page's primary-locale URL — the multilingual discovery markup.</li>
 * </ul>
 *
 * <p>Delivered as an ordinary edge-injected contributor (the platform dogfooding its own extension point),
 * so this markup lands on every page independently of the tenant's own master — the multi-tenant rule that
 * no kernel SEO code lives inside a tenant's templates. A pure, stateless, thread-safe function of the
 * {@link HeadContext}: it reads no request and no store, and contributes nothing when there is neither a
 * canonical URL, a {@code noindex} directive, nor any alternate to emit.
 */
@Component
@Order(80) // before icons (90) and the SEO tags (100): canonical/robots lead the injected head block
public class CanonicalHeadContributor implements HeadContributor {

    @Override
    public String key() {
        return "runway-core-canonical";
    }

    @Override
    public String render(HeadContext context) {
        StringBuilder out = new StringBuilder();
        // The robots directive list, assembled from the effective indexability and the page's own snippet
        // setting. They are INDEPENDENT: a page can be indexed but not quoted, which is the documented control
        // over whether generative features may use its text. Emitted as ONE meta rather than two, because a
        // second robots meta is not additive — an engine may take either one and ignore the other.
        java.util.List<String> robots = new java.util.ArrayList<>(2);
        if (context.noindex()) {
            // follow is intentional: don't index THIS URL, but do crawl its outbound links.
            robots.add("noindex, follow");
        }
        if (context.page() != null && context.page().nosnippet()) {
            robots.add("nosnippet");
        }
        if (!robots.isEmpty()) {
            append(out, "<meta name=\"robots\" content=\"" + String.join(", ", robots) + "\">");
        }
        if (context.canonical() != null && !context.canonical().isBlank()) {
            append(out, "<link rel=\"canonical\" href=\"" + escapeAttribute(context.canonical()) + "\">");
        }
        for (Alternate alternate : context.alternates()) {
            append(out, "<link rel=\"alternate\" hreflang=\"" + escapeAttribute(alternate.hreflang())
                    + "\" href=\"" + escapeAttribute(alternate.url()) + "\">");
        }
        if (context.xDefault() != null && !context.xDefault().isBlank()) {
            append(out, "<link rel=\"alternate\" hreflang=\"x-default\" href=\""
                    + escapeAttribute(context.xDefault()) + "\">");
        }
        return out.toString();
    }

    /** Appends {@code markup} on its own line (a newline before every entry after the first). */
    private static void append(StringBuilder out, String markup) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(markup);
    }

    /** Minimal HTML attribute-context escaping so a value can never break out of a {@code "…"} attribute. */
    private static String escapeAttribute(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '"' -> escaped.append("&quot;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
