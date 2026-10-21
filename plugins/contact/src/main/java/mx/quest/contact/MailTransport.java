package mx.quest.contact;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * The send seam: the single point where a built {@link MimeMessage} actually leaves the JVM over SMTP. Factored
 * out as an interface so the {@link ContactMailer}'s build-and-send logic can be unit-tested against a mock
 * transport with <b>no socket opened</b> (the production {@link JakartaMailTransport} delegates to
 * {@code jakarta.mail.Transport}).
 *
 * <p>Implementations MUST NOT swallow a failure — a broken relay has to be reported so the {@link ContactMailer}
 * can surface it (the stored copy of the submission still lands regardless). A {@link MessagingException} means
 * the send failed; returning normally means it was handed to the relay.
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
