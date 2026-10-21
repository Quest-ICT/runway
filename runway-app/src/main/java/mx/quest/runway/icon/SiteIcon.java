package mx.quest.runway.icon;

import java.util.Optional;

/**
 * The fixed set of icon slots a site may provide. Each slot is a stable {@code key} (used by the upload
 * screen so the filename on disk is never attacker-chosen), the public {@code url} it is served at, the
 * {@code storagePath} inside the site folder, and its {@code contentType}. A closed enum — not a free-form
 * filename — is the isolation guarantee: a request or upload can only ever address one of these slots.
 */
public enum SiteIcon {

    SVG_FAVICON("svg-favicon", "/favicon.svg", "icons/favicon.svg", "image/svg+xml"),
    FAVICON_ICO("favicon-ico", "/favicon.ico", "icons/favicon.ico", "image/x-icon"),
    APPLE_TOUCH("apple-touch", "/apple-touch-icon.png", "icons/apple-touch-icon.png", "image/png"),
    ICON_192("icon-192", "/icon-192.png", "icons/icon-192.png", "image/png"),
    ICON_512("icon-512", "/icon-512.png", "icons/icon-512.png", "image/png");

    private final String key;
    private final String url;
    private final String storagePath;
    private final String contentType;

    SiteIcon(String key, String url, String storagePath, String contentType) {
        this.key = key;
        this.url = url;
        this.storagePath = storagePath;
        this.contentType = contentType;
    }

    /** The stable form/slot key (e.g. {@code apple-touch}). */
    public String key() {
        return key;
    }

    /** The public URL this icon is served at (e.g. {@code /apple-touch-icon.png}). */
    public String url() {
        return url;
    }

    /** The path inside the site folder where the bytes live (e.g. {@code icons/apple-touch-icon.png}). */
    public String storagePath() {
        return storagePath;
    }

    /** The MIME type to serve this icon with (from the slot, never sniffed from content). */
    public String contentType() {
        return contentType;
    }

    /** The slot with this stable {@link #key()}, or empty if none — for validating an upload/delete request. */
    public static Optional<SiteIcon> fromKey(String key) {
        for (SiteIcon icon : values()) {
            if (icon.key.equals(key)) {
                return Optional.of(icon);
            }
        }
        return Optional.empty();
    }

    /** The slot served at this public {@link #url()}, or empty — for resolving an inbound request path. */
    public static Optional<SiteIcon> fromUrl(String url) {
        for (SiteIcon icon : values()) {
            if (icon.url.equals(url)) {
                return Optional.of(icon);
            }
        }
        return Optional.empty();
    }
}
