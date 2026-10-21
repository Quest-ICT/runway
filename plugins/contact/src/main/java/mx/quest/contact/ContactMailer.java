package mx.quest.contact;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import mx.quest.runway.spi.service.PluginSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and sends the notification email for a contact submission over the <b>site's own</b> SMTP relay, then
 * reports the outcome as a {@link MailSendResult}. It NEVER stores anything and NEVER throws for an operational
 * failure — the {@link ContactEndpoint} always stores the submission first, so a relay problem degrades to a
 * reported (not lost) message.
 *
 * <p><b>Isolation.</b> The relay coordinates come only from {@link SmtpSettings#from} over this site's settings
 * ({@link PluginSettings}, bound by the kernel to this plugin), so a site sends exclusively through the relay it
 * configured (a tenant can never send through another's). The password is the site's {@code smtpPassword} secret,
 * read in clear with {@link PluginSettings#secret} at send time and never kept.
 *
 * <p><b>Degrade-don't-crash.</b> Three non-fatal outcomes are reported rather than thrown:
 * <ul>
 *   <li>{@link MailSendResult#notConfigured()} — no host/recipient set (nothing to send to);</li>
 *   <li>{@link MailSendResult#providerMissing()} — the Jakarta Mail classes are not on the classloader (a
 *       {@link NoClassDefFoundError}/{@link LinkageError} at the seam). The kernel now bundles Jakarta Mail as a
 *       core capability, so this is effectively unreachable; the guard is kept purely so a broken deployment can
 *       never crash the request;</li>
 *   <li>{@link MailSendResult#failed(String)} — a configured relay rejected the message or was unreachable.</li>
 * </ul>
 * The build/send seam is {@link MailTransport}, injected so tests exercise the full MIME build against a mock
 * with no socket opened.
 */
public class ContactMailer {

    private static final Logger log = LoggerFactory.getLogger(ContactMailer.class);
    private static final int MAX_SUBJECT_SOURCE = 120;
    /** The declared {@code type: secret} key of the SMTP password (plugin.yml). */
    static final String PASSWORD_KEY = "smtpPassword";

    private final PluginSettings settings;
    private final MailTransport transport;

    public ContactMailer(PluginSettings settings, MailTransport transport) {
        this.settings = settings;
        this.transport = transport;
    }

    /** This site's current SMTP settings (the secret password in clear). Used by the send and the readiness badge. */
    public SmtpSettings settingsFor(String siteId) {
        return SmtpSettings.from(settings.site(siteId), settings.secret(siteId, PASSWORD_KEY));
    }

    /**
     * Emails a stored submission's contents to the site's recipient. Never throws for an operational failure —
     * the outcome is always a {@link MailSendResult}.
     *
     * @param siteId      the bound site (its own relay is used)
     * @param displayName the site's display name (for the subject / body context)
     * @param name        the submitter's name
     * @param email       the submitter's email (used as Reply-To so a reply reaches the visitor)
     * @param message     the message body
     */
    public MailSendResult send(String siteId, String displayName, String name, String email, String message) {
        SmtpSettings smtp = settingsFor(siteId);
        if (!smtp.isConfigured()) {
            return MailSendResult.notConfigured();
        }
        String subject = "[" + displayName + "] " + subjectExcerpt(name, message);
        String body = "New contact submission on " + displayName + "\n\n"
                + "Name:    " + orDash(name) + "\n"
                + "Email:   " + orDash(email) + "\n\n"
                + message;
        return dispatch(smtp, smtp.effectiveFrom(), smtp.to(), subject, body, blankToNull(email));
    }

    /**
     * Sends a diagnostic test message to {@code testTo} over the site's relay, to verify the SMTP configuration
     * from the console. Same degrade contract as {@link #send} — the outcome is reported, never thrown.
     */
    public MailSendResult sendTest(String siteId, String displayName, String testTo) {
        SmtpSettings smtp = settingsFor(siteId);
        // A test still needs a usable relay (host + a configured recipient) even though we override the To.
        if (!smtp.isConfigured()) {
            return MailSendResult.notConfigured();
        }
        String to = (testTo == null || testTo.isBlank()) ? smtp.to() : testTo.strip();
        String body = "This is a test message from the Runway contact plugin for site '" + displayName + "'.\n"
                + "If you received it, the site's SMTP relay is configured correctly.";
        return dispatch(smtp, smtp.effectiveFrom(), to, "[" + displayName + "] SMTP test", body, null);
    }

    private MailSendResult dispatch(SmtpSettings smtp, String from, String to, String subject, String body,
                                    String replyTo) {
        try {
            MimeMessage msg = build(smtp, from, to, subject, body, replyTo);
            transport.send(msg);
            return MailSendResult.sent();
        } catch (MessagingException e) {
            log.warn("contact mail send failed for relay {}: {}", smtp, e.toString());
            return MailSendResult.failed(rootMessage(e));
        } catch (LinkageError e) {
            // Jakarta Mail not on the classpath. The kernel bundles it as a core capability, so this is
            // effectively unreachable; guard it anyway so a broken deployment never crashes the request.
            log.warn("contact mail send skipped: Jakarta Mail unavailable on the classloader: {}", e.toString());
            return MailSendResult.providerMissing();
        }
    }

    /** Builds the MIME message from the site's relay settings. Package-visible for direct MIME-shape testing. */
    MimeMessage build(SmtpSettings smtp, String from, String to, String subject, String body, String replyTo)
            throws MessagingException {
        Session session = session(smtp);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        if (replyTo != null) {
            try {
                msg.setReplyTo(InternetAddress.parse(replyTo, false));
            } catch (MessagingException badReplyTo) {
                // A visitor-supplied email that isn't a valid address must not block the notification.
                log.debug("ignoring invalid Reply-To '{}': {}", replyTo, badReplyTo.toString());
            }
        }
        msg.setSubject(subject, StandardCharsets.UTF_8.name());
        msg.setText(body, StandardCharsets.UTF_8.name());
        return msg;
    }

    /**
     * A Jakarta Mail {@link Session} from the relay settings (host/port, TLS mode + auth).
     *
     * <p><b>The protocol is always {@code smtp}, even for implicit TLS.</b> We send with the static
     * {@link jakarta.mail.Transport#send(jakarta.mail.Message)}, which resolves an {@code rfc822} recipient to
     * the {@code smtp} transport via Jakarta Mail's address map — that lookup <b>ignores</b>
     * {@code mail.transport.protocol}. Selecting {@code smtps} therefore leaves {@code mail.smtp.host} unset, so
     * the send silently dials {@code localhost:25} and fails with "Connection refused". Implicit TLS (SMTPS, e.g.
     * port 465) must instead ride {@code mail.smtp.ssl.enable}; explicit TLS (e.g. 587) rides
     * {@code mail.smtp.starttls.enable}. Package-visible so a test can assert the resolved properties.
     */
    static Session session(SmtpSettings smtp) {
        boolean ssl = smtp.ssl();
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", smtp.host());
        props.put("mail.smtp.port", String.valueOf(smtp.port()));
        props.put("mail.smtp.auth", String.valueOf(smtp.auth()));
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");   // implicit TLS on connect (SMTPS, e.g. 465)
        } else {
            props.put("mail.smtp.starttls.enable", String.valueOf(smtp.starttls()));  // opportunistic STARTTLS (e.g. 587)
        }
        if (smtp.auth()) {
            Authenticator auth = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(smtp.username(), smtp.password());
                }
            };
            return Session.getInstance(props, auth);
        }
        return Session.getInstance(props);
    }

    private static String subjectExcerpt(String name, String message) {
        String source = (name != null && !name.isBlank()) ? name : message;
        String s = source == null ? "message" : source.strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            s = "message";
        }
        return s.length() <= MAX_SUBJECT_SOURCE ? s : s.substring(0, MAX_SUBJECT_SOURCE).stripTrailing() + "…";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return (m == null || m.isBlank()) ? cur.getClass().getSimpleName() : m;
    }

    private static String orDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value.strip();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
