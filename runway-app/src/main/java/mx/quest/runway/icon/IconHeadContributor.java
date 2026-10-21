package mx.quest.runway.icon;

import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.IconHints;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Emits a site's icon and Web-App-Manifest discovery tags into every page's {@code <head>} — the
 * {@code <link rel="icon">}, {@code rel="apple-touch-icon">} and {@code rel="manifest">} references and the
 * {@code theme-color} meta — so a browser actually uses the icons a site uploaded. A kernel
 * {@link HeadContributor}, edge-injected exactly like the SEO one, and a pure function of the host-resolved
 * {@link IconHints}: it advertises only the slots the site provides (nothing missing), and contributes the
 * empty string when the site has no icons.
 */
@Component
@Order(90) // icons before the SEO contributor (100), purely for stable, readable head order
public class IconHeadContributor implements HeadContributor {

    @Override
    public String key() {
        return "runway-core-icons";
    }

    @Override
    public String render(HeadContext context) {
        IconHints hints = context.icons();
        if (hints == null || hints.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String key : hints.iconKeys()) {
            SiteIcon.fromKey(key).ifPresent(icon -> appendIconLink(out, icon));
        }
        if (hints.hasManifest()) {
            newline(out);
            out.append("<link rel=\"manifest\" href=\"/manifest.webmanifest\">");
        }
        if (hints.themeColor() != null && !hints.themeColor().isBlank()) {
            newline(out);
            out.append("<meta name=\"theme-color\" content=\"").append(escapeAttribute(hints.themeColor())).append("\">");
        }
        return out.toString();
    }

    /** Head links exist for the favicons and the apple-touch icon; the PNG sizes are manifest-only. */
    private static void appendIconLink(StringBuilder out, SiteIcon icon) {
        String tag = switch (icon) {
            case SVG_FAVICON -> "<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">";
            case FAVICON_ICO -> "<link rel=\"icon\" sizes=\"any\" href=\"/favicon.ico\">";
            case APPLE_TOUCH -> "<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">";
            default -> null;
        };
        if (tag != null) {
            newline(out);
            out.append(tag);
        }
    }

    private static void newline(StringBuilder out) {
        if (out.length() > 0) {
            out.append('\n');
        }
    }

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

    /**
     * The manifest and the icon folder.
     *
     * <p>The folder rather than each slot: this contributor advertises which icons EXIST, so it is the
     * listing that shapes the markup — one directory read, and it catches an upload or a deletion without
     * enumerating (or reading the bytes of) every slot on every request.
     */
    @Override
    public java.util.List<String> headInputs(String siteId) {
        return java.util.List.of("manifest.yml", "icons");
    }
}
