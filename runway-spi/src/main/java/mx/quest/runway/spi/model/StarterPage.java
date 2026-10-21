package mx.quest.runway.spi.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A page a plugin offers to install on a site ({@link mx.quest.runway.spi.service.SitePages#install}). Immutable.
 *
 * @param slug    the page's stable id and file name (a safe identifier, never {@code null})
 * @param path    its public path, starting with {@code /} (never {@code null})
 * @param titles  the page title per language code ({@code "en" → "Contact"}); copied, {@code null} keys and
 *                values dropped; may be empty
 * @param html    the page body: decoupled-Thymeleaf markup (never {@code null})
 * @param logic   the decoupled logic ({@code .th.xml}) for the body, or {@code null}/blank for none
 * @param noindex whether the page asks search engines not to index it
 * @since SPI 0.5.0
 */
public record StarterPage(String slug, String path, Map<String, String> titles, String html, String logic,
                          boolean noindex) {

    public StarterPage {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(html, "html");
        Map<String, String> copy = new LinkedHashMap<>();
        if (titles != null) {
            titles.forEach((language, title) -> {
                if (language != null && title != null) {
                    copy.put(language, title);
                }
            });
        }
        titles = Map.copyOf(copy);
        logic = (logic == null || logic.isBlank()) ? null : logic;
    }
}
