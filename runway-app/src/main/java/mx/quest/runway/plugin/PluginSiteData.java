package mx.quest.runway.plugin;

import java.util.List;
import java.util.Objects;

/**
 * What a plugin declares about the data it keeps in each site's folder (descriptor key {@code siteData:}).
 *
 * <p>Today one list: {@code personal} — the paths under the plugin's per-site subtree
 * ({@code sites/<id>/plugins/<pluginId>/}) that hold <b>personal data</b> (anything a visitor or a user typed: names,
 * emails, messages, search terms). A <b>transfer copy</b> of a site leaves every declared path out, because it is
 * meant for someone outside the organisation; a full backup keeps everything. A path ending in {@code /} names a
 * folder (everything under it); any other path names one file.
 *
 * <h2>Why a declaration and not a guess</h2>
 * The kernel cannot tell personal data from content by looking at a file, and a sanitiser that guesses wrong is
 * worse than none because it is trusted. The plugin author knows. A plugin that is <b>not loaded</b> when a
 * transfer copy is made has no declaration the kernel can read, so its whole folder is left out (fail-safe).
 *
 * @param personal the declared personal paths, relative to the plugin's subtree (never {@code null}; blank and
 *                 {@code null} entries dropped)
 */
public record PluginSiteData(List<String> personal) {

    public PluginSiteData {
        personal = personal == null ? List.of()
                : personal.stream().filter(Objects::nonNull).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    /** No declaration: nothing personal. */
    public static PluginSiteData none() {
        return new PluginSiteData(List.of());
    }

    /**
     * Whether {@code relPath} (relative to the plugin's subtree, {@code /}-separated) is declared personal: it equals a
     * declared file, or lies under a declared folder.
     */
    public boolean isPersonal(String relPath) {
        return declarationCovering(relPath) != null;
    }

    /** The declaration covering {@code relPath} (as written), or {@code null}. */
    public String declarationCovering(String relPath) {
        if (relPath == null) {
            return null;
        }
        for (String declared : personal) {
            if (declared.endsWith("/") ? relPath.startsWith(declared) : relPath.equals(declared)) {
                return declared;
            }
        }
        return null;
    }

    /**
     * Why {@code path} is not an acceptable declaration, or {@code null}: it must be relative, use {@code /}, and have
     * no empty, {@code .} or {@code ..} segment and no control character — the same rules the per-site file service
     * applies to the paths a plugin writes, so every file a plugin can write can be declared.
     */
    static String problemWith(String path) {
        if (path.startsWith("/")) {
            return "is absolute";
        }
        if (path.indexOf('\\') >= 0) {
            return "contains a backslash";
        }
        String body = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (body.isEmpty()) {
            return "names no file or folder";
        }
        for (String segment : body.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "has an empty, '.' or '..' segment";
            }
        }
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) < 0x20 || path.charAt(i) == 0x7f) {
                return "contains a control character";
            }
        }
        return null;
    }
}
