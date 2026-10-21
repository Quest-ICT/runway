package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;

/**
 * Finds every attribute of a rendered page whose value is a first-party {@code /__assets/...} URL and reports each
 * one that does not carry {@code v=<expected token>}. An unversioned reference is served {@code no-cache}, and one
 * with a different token would be cached for a day under the wrong release — either is a regression of the
 * "after an upgrade the console ran the previous release's script" defect.
 *
 * <p>Attributes, not raw text: HTML comments legitimately mention {@code /__assets/...} paths.
 */
public final class AssetVersionAudit {

    private AssetVersionAudit() {
    }

    /**
     * The problems on {@code html} (empty when every {@code /__assets} reference carries {@code v=<token>}).
     *
     * @param where label prefixed to each problem (the URL or template rendered)
     * @param html  the rendered page
     * @param token the expected {@link AssetVersion} token
     */
    public static List<String> audit(String where, String html, String token) {
        List<String> problems = new ArrayList<>();
        for (Element el : Jsoup.parse(html).getAllElements()) {
            for (Attribute attr : el.attributes()) {
                String value = attr.getValue();
                if (value.startsWith("/__assets/") && !hasToken(value, token)) {
                    problems.add(where + ": <" + el.tagName() + " " + attr.getKey() + "=\"" + value
                            + "\"> is not versioned with ?v=" + token);
                }
            }
        }
        return problems;
    }

    /** How many {@code /__assets} references {@code html} has — so a caller can prove the audit was not vacuous. */
    public static int count(String html) {
        int n = 0;
        for (Element el : Jsoup.parse(html).getAllElements()) {
            for (Attribute attr : el.attributes()) {
                if (attr.getValue().startsWith("/__assets/")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean hasToken(String url, String token) {
        int q = url.indexOf('?');
        if (q < 0) {
            return false;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            if (pair.equals(AssetVersion.PARAM + "=" + token)) {
                return true;
            }
        }
        return false;
    }
}
