package mx.quest.runway.site;

/**
 * A site's stored <b>contact information</b> — the plain, human-facing ways to reach the site owner: an email
 * address, a phone number, and a free-text postal/office address. A site manager edits these on the tenant
 * config screen; a tenant template surfaces them (a footer, a "contact us" block) via the render model, or a
 * plugin (a contact form) may read them.
 *
 * <p>Persisted as {@code sites/<id>/contact.yml} inside the tenant folder, so it travels in the site's backup
 * with no special handling and can never be read by another tenant (the id resolves only its own folder,
 * traversal-safely). Every field is optional; a blank/absent value means "not provided", and an absent file
 * resolves to {@link #EMPTY}.
 *
 * <p><b>Deliberately narrow, and pure data.</b> This is contact <em>information</em>, not a contact-<em>form</em>
 * feature: the kernel does not send mail. Delivery (SMTP recipients, a submission handler) is a plugin concern,
 * and the address free-text is emitted through the render model's default-escaping {@code ${...}}, so a value is
 * shown as text, never as markup. Runway keeps the kernel field set to the tenant-owned information and leaves
 * a contact <em>form</em> (recipient list + sender) to a plugin — the honest tenant/plugin boundary.
 *
 * @param email   a public contact email address, or {@code null}/blank when not provided
 * @param phone   a public contact phone number, or {@code null}/blank when not provided
 * @param address a free-text postal/office address (may contain line breaks), or {@code null}/blank when unset
 */
public record SiteContact(String email, String phone, String address) {

    /** The all-unset contact — what an absent {@code contact.yml} resolves to. */
    public static final SiteContact EMPTY = new SiteContact(null, null, null);

    /** Whether every field is unset (so a template can hide an empty contact block). */
    public boolean isEmpty() {
        return blank(email) && blank(phone) && blank(address);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
