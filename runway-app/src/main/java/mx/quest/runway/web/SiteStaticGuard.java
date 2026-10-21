package mx.quest.runway.web;

/**
 * A veto over public static-asset serving: given the current site and a path under its {@code static/}
 * folder, decides whether that file must be withheld (served as a {@code 404}) even though it is present
 * on disk. The kernel ships no guards; the documents screen contributes one so a <em>disabled</em>
 * document is hidden from the public without deleting its bytes.
 *
 * <p>All registered guards are consulted; a single {@code true} withholds the file. A guard must be a
 * fast, side-effect-free predicate — it runs on every {@code /static/**} request.
 */
public interface SiteStaticGuard {

    /**
     * @param siteId        the site whose asset is being requested (already resolved from the host)
     * @param staticRelPath the requested path relative to the site's {@code static/} folder, e.g.
     *                      {@code "doc/report.pdf"} or {@code "css/site.css"} (never leading-slashed)
     * @return {@code true} to withhold the file (respond {@code 404}); {@code false} to allow serving
     */
    boolean isWithheld(String siteId, String staticRelPath);
}
