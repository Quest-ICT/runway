package mx.quest.runway.mail;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.springframework.stereotype.Component;

/**
 * Sends <b>application-level</b> email through the platform's own SMTP account (configured on the Settings
 * screen, held in {@code external/platform.yml}). Used for password recovery, and any future operator
 * notifications, for <b>both</b> realms.
 *
 * <p><b>Scope boundary.</b> This is the PLATFORM mailer only. It is deliberately separate from a site's
 * contact-form relay: the platform never sends a tenant's mail with it, and a tenant never sends through it.
 * The platform account and a site's own SMTP relay are configured, stored and used independently.
 *
 * <p><b>Secret handling.</b> The password is stored {@code ENC(...)}-encrypted in the settings; it is decrypted
 * here (via the injected {@link SecretCipher}) only at send time, handed straight to Jakarta Mail, and never
 * logged ({@link PlatformSettings.Smtp#toString()} masks it).
 *
 * <p>The build/send seam is {@link MailTransport}, injected so the full MIME build is unit-testable against a
 * mock with no socket opened (the production {@link JakartaMailTransport} delegates to {@code jakarta.mail
 * .Transport}). Jakarta Mail is a core kernel capability (bundled in the app jar), so no plugin is required.
 */
@Component
public class PlatformMailer {

    /** Submission port default when none is configured (STARTTLS submission, RFC 6409). */
    private static final int DEFAULT_PORT = 587;
    /** How long to wait for the relay to accept the connection. */
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** How long one read from, or write to, the relay may take. */
    static final int IO_TIMEOUT_MILLIS = 30_000;

    private final PlatformSettingsStore settings;
    private final SecretCipher cipher;
    private final MailTransport transport;

    public PlatformMailer(PlatformSettingsStore settings, SecretCipher cipher, MailTransport transport) {
        this.settings = settings;
        this.cipher = cipher;
        this.transport = transport;
    }

    /**
     * Whether a usable platform mail account is configured (a non-blank host and From address). When false the
     * "forgot password?" affordance is hidden and recovery cannot send — an operator must configure SMTP first.
     */
    public boolean isConfigured() {
        PlatformSettings.Smtp smtp = settings.get().smtp();
        return smtp != null && smtp.isConfigured();
    }

    /**
     * Sends a plain-text message through the platform relay.
     *
     * @param to      the recipient address
     * @param subject the subject line
     * @param body    the plain-text body
     * @throws MessagingException if the platform mail account is not configured, or the relay rejected the
     *                            message or was unreachable
     */
    public void send(String to, String subject, String body) throws MessagingException {
        PlatformSettings.Smtp smtp = settings.get().smtp();
        if (smtp == null || !smtp.isConfigured()) {
            throw new MessagingException("platform mail is not configured");
        }
        transport.send(build(smtp, to, subject, body));
    }

    /** Builds the MIME message from the platform relay settings. Package-visible for direct MIME-shape testing. */
    MimeMessage build(PlatformSettings.Smtp smtp, String to, String subject, String body) throws MessagingException {
        MimeMessage msg = new MimeMessage(session(smtp));
        msg.setFrom(new InternetAddress(smtp.from()));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        msg.setSubject(subject, StandardCharsets.UTF_8.name());
        msg.setText(body, StandardCharsets.UTF_8.name());
        return msg;
    }

    /**
     * A Jakarta Mail {@link Session} from the relay settings (host/port, TLS mode + auth).
     *
     * <p><b>Why the protocol is always {@code smtp} — even for implicit TLS.</b> We send with the static
     * {@link jakarta.mail.Transport#send(Message)}, which selects the transport for an {@code rfc822} recipient
     * through Jakarta Mail's <i>address map</i> ({@code rfc822 → smtp}); that lookup <b>ignores</b>
     * {@code mail.transport.protocol}. Setting the protocol to {@code smtps} therefore does <i>not</i> make the
     * static send use the SMTPS provider — it still picks the plaintext {@code smtp} transport, and because only
     * the {@code mail.smtps.*} host/port were set, {@code mail.smtp.host} defaults to {@code localhost} and the
     * send fails with a spurious "Connection refused". So we keep {@code smtp} and choose the TLS mode with a
     * property instead:
     * <ul>
     *   <li><b>implicit TLS</b> (SMTPS, e.g. port 465) → {@code mail.smtp.ssl.enable=true} — the socket is
     *       wrapped in TLS from the first byte;</li>
     *   <li><b>explicit TLS</b> (e.g. port 587) → {@code mail.smtp.starttls.enable=true} — plaintext connect,
     *       upgraded to TLS via {@code STARTTLS}.</li>
     * </ul>
     * The two are mutually exclusive and driven by the {@code ssl} / {@code starttls} settings.
     *
     * <p>Package-visible so a test can assert the resolved Jakarta Mail properties directly.
     */
    Session session(PlatformSettings.Smtp smtp) throws MessagingException {
        boolean ssl = Boolean.TRUE.equals(smtp.ssl());
        boolean starttls = Boolean.TRUE.equals(smtp.starttls());
        int port = smtp.port() != null ? smtp.port() : DEFAULT_PORT;
        boolean auth = Boolean.TRUE.equals(smtp.auth());
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", smtp.host());
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(auth));
        // Bounded waits: an unreachable or stalled relay fails a recovery request in seconds instead of holding a
        // request thread on Jakarta Mail's default of waiting forever.
        props.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MILLIS));
        props.put("mail.smtp.timeout", String.valueOf(IO_TIMEOUT_MILLIS));
        props.put("mail.smtp.writetimeout", String.valueOf(IO_TIMEOUT_MILLIS));
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");   // implicit TLS on connect (SMTPS, e.g. 465)
            props.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if (starttls) {
            // STARTTLS when asked for is REQUIRED, not opportunistic: a relay (or a man in the middle) that does not
            // offer it fails the send instead of receiving the credentials and the reset link in the clear.
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.checkserveridentity", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "false");
        }
        if (auth) {
            String username = smtp.username();
            String password;
            try {
                password = cipher.decrypt(smtp.password() == null ? "" : smtp.password());
            } catch (RuntimeException unreadable) {
                // Surface it as a mail failure, so every caller's MessagingException handling applies.
                throw new MessagingException("platform mail password cannot be decrypted (wrong "
                        + "runway.security.secret-key or corrupted value)", unreadable);
            }
            Authenticator authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            };
            return Session.getInstance(props, authenticator);
        }
        return Session.getInstance(props);
    }
}
