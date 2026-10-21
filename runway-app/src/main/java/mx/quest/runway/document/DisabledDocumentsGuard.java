package mx.quest.runway.document;

import mx.quest.runway.web.SiteStaticGuard;
import org.springframework.stereotype.Component;

/**
 * Withholds a <b>disabled</b> document from public serving: when a request under {@code static/doc/} targets
 * a document the site manager has disabled, this guard vetoes it so {@link mx.quest.runway.web.SiteStaticController}
 * responds {@code 404} even though the file is still on disk. This is the "disable = withhold without deleting"
 * mechanic, wired through the generic guard seam so the kernel serving code stays document-agnostic.
 */
@Component
public class DisabledDocumentsGuard implements SiteStaticGuard {

    private static final String DOC_PREFIX = "doc/";

    private final DocumentService documents;

    public DisabledDocumentsGuard(DocumentService documents) {
        this.documents = documents;
    }

    @Override
    public boolean isWithheld(String siteId, String staticRelPath) {
        if (!staticRelPath.startsWith(DOC_PREFIX)) {
            return false; // only the documents subtree is subject to disabling
        }
        String docRel = staticRelPath.substring(DOC_PREFIX.length());
        return documents.isDisabled(siteId, docRel);
    }
}
