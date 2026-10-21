package mx.quest.runway.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

/**
 * The production {@link MailTransport}: hands the message to {@code jakarta.mail.Transport}, which resolves the
 * {@code smtp}/{@code smtps} provider from the message's {@link jakarta.mail.Session} (the Angus reference
 * implementation, bundled in the kernel and discovered at runtime via {@code META-INF/services/jakarta.mail
 * .Provider} on the application classloader).
 *
 * <p>Authentication is carried on the {@code Session} that {@link PlatformMailer} built (with
 * {@code mail.smtp.auth} and a static {@link jakarta.mail.Authenticator} when the relay requires it), so
 * {@link Transport#send(jakarta.mail.Message)} with the message alone is sufficient — no separate
 * connect/credentials call. Stateless and thread-safe.
 */
@Component
public class JakartaMailTransport implements MailTransport {

    @Override
    public void send(MimeMessage message) throws MessagingException {
        Transport.send(message);
    }
}
