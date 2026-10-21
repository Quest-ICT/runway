package mx.quest.runway.web;

/**
 * The canonical location of the per-site health probe, in one place so no other class re-types the literal.
 *
 * <p>The path is <b>fixed, not configurable</b>: a configurable path could not be reserved, and reservation is
 * what stops a tenant publishing a page that shadows the probe (the {@code /setup} lesson — a kernel path that a
 * tenant can occupy is a defect, not a convention). The {@code /__} prefix earns all three legs of reservation
 * with no new rule: {@link ReservedPaths#atRequestTime} 404s it as content, {@code atEnumerationTime} keeps it out
 * of the sitemap, and {@code PathSafety} rejects it at authoring time.
 */
public final class SiteHealthPaths {

    private SiteHealthPaths() {
    }

    /** The per-site health probe, served on a tenant host by {@code SiteHealthFilter}. */
    public static final String PATH = "/__health";
}
