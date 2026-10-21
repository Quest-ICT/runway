package mx.quest.contact;

/**
 * The reported outcome of trying to email a contact submission. A submission is <b>always</b> stored first
 * (belt-and-suspenders), so this result is <em>advisory</em> — it never means the message was lost, only whether
 * the email went out. The {@link ContactEndpoint} uses it to decide what the visitor sees; the plugin logs a
 * failure but keeps the stored copy.
 */
public record MailSendResult(Status status, String detail) {

    /** Which way the send went. */
    public enum Status {
        /** The message was handed to the site's SMTP relay successfully. */
        SENT,
        /** No relay is configured for this site (blank host or recipient) — nothing was sent, only stored. */
        NOT_CONFIGURED,
        /** Jakarta Mail is not on the classpath (a broken deployment; the kernel bundles it) — stored only. */
        PROVIDER_MISSING,
        /** A relay is configured but the send failed (rejected / unreachable / auth) — {@link #detail} says why. */
        FAILED
    }

    /** The email was sent. */
    public static MailSendResult sent() {
        return new MailSendResult(Status.SENT, null);
    }

    /** No SMTP relay configured for this site (stored only). */
    public static MailSendResult notConfigured() {
        return new MailSendResult(Status.NOT_CONFIGURED, null);
    }

    /** Jakarta Mail is unavailable on the classloader (a broken deployment; stored only). */
    public static MailSendResult providerMissing() {
        return new MailSendResult(Status.PROVIDER_MISSING, null);
    }

    /** The send failed for the given reason (stored only). */
    public static MailSendResult failed(String detail) {
        return new MailSendResult(Status.FAILED, detail);
    }

    /** Whether the email actually went out (as opposed to being stored only). */
    public boolean wasSent() {
        return status == Status.SENT;
    }
}
