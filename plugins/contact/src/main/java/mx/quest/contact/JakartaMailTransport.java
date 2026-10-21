package mx.quest.contact;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

/**
 * The production {@link MailTransport}: hands the message to {@code jakarta.mail.Transport}, which resolves the
 * {@code smtp}/{@code smtps} provider from the message's {@link jakarta.mail.Session} (the Angus reference
 * implementation, discovered at runtime via {@code META-INF/services/jakarta.mail.Provider} — now supplied by
 * the KERNEL, which bundles the Jakarta Mail stack as a core capability and is reached parent-first from the
 * plugin classloader).
 *
 * <p>Authentication is carried on the {@code Session} the {@link ContactMailer} built (with {@code mail.smtp.auth}
 * and a static {@link jakarta.mail.Authenticator} when the relay requires it), so {@link Transport#send(jakarta.mail.Message)}
 * with the message alone is sufficient — no separate connect/credentials call. Stateless and thread-safe.
 */
public class JakartaMailTransport implements MailTransport {

    @Override
    public void send(MimeMessage message) throws MessagingException {
        Transport.send(message);
    }
}
