package mx.quest.runway.spi.model;

import java.util.List;

/**
 * The site's icon/manifest facts the host has resolved for the current render, handed to a
 * {@link mx.quest.runway.spi.HeadContributor} so it can emit the discovery {@code <link>}/{@code <meta>}
 * tags without reading a store. Derived from which icon slots the site actually provides, so nothing missing
 * is ever advertised.
 *
 * @param iconKeys    the stable keys of the icon slots the site provides (the contributor maps each to its
 *                    link); never {@code null} (defensively copied)
 * @param hasManifest whether a Web App Manifest is worth linking (the site declared manifest fields or has a
 *                    manifest-eligible icon)
 * @param themeColor  the browser-UI theme color to emit as {@code <meta name="theme-color">}, or {@code null}
 */
public record IconHints(List<String> iconKeys, boolean hasManifest, String themeColor) {

    /** The "nothing to emit" value — a site with no icons and no manifest. */
    public static final IconHints NONE = new IconHints(List.of(), false, null);

    public IconHints {
        iconKeys = (iconKeys == null) ? List.of() : List.copyOf(iconKeys);
    }

    /** Whether there is nothing for the contributor to emit. */
    public boolean isEmpty() {
        return iconKeys.isEmpty() && !hasManifest && (themeColor == null || themeColor.isBlank());
    }
}
