package mx.quest.runway.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * The send seam: the single point where a built {@link MimeMessage} actually leaves the JVM over SMTP. Factored
 * out as an interface so {@link PlatformMailer}'s build-and-send logic can be unit-tested against a mock
 * transport with <b>no socket opened</b> (the production {@link JakartaMailTransport} delegates to
 * {@code jakarta.mail.Transport}).
 *
 * <p>This is the platform's OWN transport, used for application-level messages (password recovery, future
 * operator notifications) over the platform SMTP account configured on the Settings screen. It is entirely
 * separate from a site's contact-form relay — a tenant never sends through this, and the platform never sends
 * a tenant's mail through it.
 *
 * <p>Implementations MUST NOT swallow a failure — a broken relay has to be reported so {@link PlatformMailer}
 * can surface it. A {@link MessagingException} means the send failed; returning normally means the message was
 * handed to the relay.
 */
public interface MailTransport {

    /**
     * Sends an already-built, already-addressed message.
     *
     * @param message the MIME message to transmit (From/To/Subject/body all set by the caller)
     * @throws MessagingException if the relay rejected the message or could not be reached
     */
    void send(MimeMessage message) throws MessagingException;
}
