package mx.quest.runway.spi.model;

import java.time.Instant;

/**
 * Who wrote a piece of content and when — the <b>provenance</b> a page carries across the SPI boundary.
 *
 * <p><b>Why this exists as its own type.</b> Before it, {@link PageMeta} carried identity, titles and
 * indexability but nothing about authorship or time, and {@link HeadContext} carried none either. The
 * consequence was structural rather than cosmetic: <b>no {@code HeadContributor} could emit a publication date
 * or an author at all</b>, because the boundary did not carry the values. Two things fell out of that, and both
 * were found by auditing the platform against its own SEO guidance:
 *
 * <ul>
 *   <li><b>Trust markers were unimplementable.</b> Named authors, visible publication dates and visible update
 *       dates are the concrete, machine-legible form of "a real, accountable entity stands behind this". A
 *       platform that cannot express them cannot help a tenant be a credible source.</li>
 *   <li><b>Structured data was pushed toward a policy violation.</b> A blog whose entries each knew their own
 *       publication date could not pass it to the schema layer, so {@code datePublished} and {@code author} had
 *       to come from hand-written per-site config matched by URL pattern. One pattern covers every entry, so the
 *       honest outcomes were "no dates at all" or "one date asserted identically on every post" — the second
 *       being an assertion that is false of nearly every page it appears on, reached by <i>trying</i> to
 *       comply.</li>
 * </ul>
 *
 * <p><b>Grouped rather than three loose components</b> so the concept has a name, so a contributor can pass it
 * around as one value, and so it can gain fields later (an author URL or affiliation) without changing the arity
 * of every record that carries it.
 *
 * <p><b>Every field is optional.</b> Most content legitimately has no byline — a landing page, a contact page, a
 * pricing table. {@link #NONE} is the "nothing known" value and is what a page without provenance carries;
 * consumers must treat absence as normal and simply emit nothing, never a placeholder or a fabricated date. A
 * wrong date is worse than no date: it is an assertion, and search engines and readers both treat it as one.
 *
 * <p><b>The division of ownership between the three fields is deliberate.</b> {@link #author()} and
 * {@link #publishedAt()} are <i>editorial facts</i> — a human asserts them and they do not change when a typo is
 * fixed. {@link #updatedAt()} is a <i>system fact</i> the platform derives. Letting an author hand-maintain a
 * "last updated" stamp guarantees it drifts out of truth, and an untruthful timestamp is worse than an absent
 * one because consumers are told to trust it.
 *
 * @param author      display name of the human or organisation credited, or {@code null}/blank when unattributed.
 *                    A free-text display name on purpose: a real identity model (accounts, profiles, affiliations)
 *                    is a larger design, and blocking bylines on it would leave content unattributable meanwhile.
 * @param publishedAt when the content was first published, or {@code null} if unknown. An editorial fact: set by
 *                    the author, not the file system, so it survives a backup/restore and a re-deploy — the
 *                    reason it is not derived from a file timestamp.
 * @param updatedAt   when the content last changed, or {@code null} if unknown. Derived by the host (typically a
 *                    file mtime) rather than authored.
 */
public record Provenance(String author, Instant publishedAt, Instant updatedAt) {

    /** Nothing known — the value for content with no byline and no dates. Never {@code null}. */
    public static final Provenance NONE = new Provenance(null, null, null);

    public Provenance {
        // Blank is the same as absent: a form posts "" for an untouched field, and an empty byline must not
        // render as an empty author element or serialize as a present-but-meaningless value.
        author = (author == null || author.isBlank()) ? null : author.trim();
    }

    /** Whether anything at all is known — {@code false} for {@link #NONE}. Lets a consumer skip a whole block. */
    public boolean isPresent() {
        return author != null || publishedAt != null || updatedAt != null;
    }

    /** This provenance with {@code updatedAt} replaced — the host derives that field after the fact. */
    public Provenance withUpdatedAt(Instant instant) {
        return new Provenance(author, publishedAt, instant);
    }
}
