package mx.quest.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import java.util.HashMap;
import java.util.Map;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The send path: {@link ContactMailer} builds the expected MIME message and hands it to a MOCK
 * {@link MailTransport} (no socket), reports a send failure without throwing, reports "not configured" when no
 * relay is set, and takes the SMTP password ONLY from {@code PluginSettings.secret(site, "smtpPassword")} — the
 * kernel decrypts the {@code ENC(...)} value; the plugin never sees ciphertext, never reads a password out of the
 * settings view, and never prints it.
 */
class ContactMailerTest {

    private static final Map<String, Object> RELAY = Map.of(
            "smtpHost", "mail.example.com",
            "smtpPort", 587L,
            "smtpAuth", true,
            "smtpUsername", "postmaster",
            "smtpStarttls", true,
            "fromAddress", "site@example.com",
            "toAddress", "owner@example.com");

    /** The plugin's per-site settings as the kernel hands them over: RELAY for acme, the password a secret. */
    private static FakePluginSettings settings(Map<String, Object> acme) {
        return new FakePluginSettings().site("acme", acme).secret("acme", "smtpPassword", "s3cret");
    }

    /** The password the built session's authenticator would answer the relay with. */
    private static PasswordAuthentication credentials(Session session) {
        return session.requestPasswordAuthentication(null, 587, "smtp", null, null);
    }

    @Test
    void aSubmissionBuildsTheExpectedMimeMessageAndCallsTheTransport() throws Exception {
        MailTransport transport = mock(MailTransport.class);
        ContactMailer mailer = new ContactMailer(settings(RELAY), transport);

        MailSendResult result = mailer.send("acme", "Acme", "Ann", "ann@example.com", "Hello there");

        assertEquals(MailSendResult.Status.SENT, result.status());
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(captor.capture());
        MimeMessage msg = captor.getValue();
        assertEquals("site@example.com", ((jakarta.mail.internet.InternetAddress) msg.getFrom()[0]).getAddress());
        assertEquals("owner@example.com",
                ((jakarta.mail.internet.InternetAddress) msg.getRecipients(Message.RecipientType.TO)[0]).getAddress());
        // Reply-To is the visitor so the site can reply straight to them.
        assertEquals("ann@example.com",
                ((jakarta.mail.internet.InternetAddress) msg.getReplyTo()[0]).getAddress());
        assertTrue(msg.getSubject().startsWith("[Acme] "), "subject is site-prefixed: " + msg.getSubject());
        String body = (String) msg.getContent();
        assertTrue(body.contains("ann@example.com"), "the body carries the submitter email");
        assertTrue(body.contains("Hello there"), "the body carries the message");
    }

    @Test
    void aTransportFailureIsReportedNotThrown() throws Exception {
        MailTransport transport = mock(MailTransport.class);
        doThrow(new MessagingException("relay refused")).when(transport).send(any());
        ContactMailer mailer = new ContactMailer(settings(RELAY), transport);

        MailSendResult result = mailer.send("acme", "Acme", "Ann", "ann@example.com", "Hi");

        assertEquals(MailSendResult.Status.FAILED, result.status());
        assertNotNull(result.detail());
        assertTrue(result.detail().contains("relay refused"), "the reason is surfaced: " + result.detail());
    }

    @Test
    void anUnconfiguredRelayReportsNotConfiguredAndNeverTouchesTheTransport() throws Exception {
        MailTransport transport = mock(MailTransport.class);
        // Blank host + recipient ⇒ not configured.
        ContactMailer mailer = new ContactMailer(settings(Map.of()), transport);

        MailSendResult result = mailer.send("acme", "Acme", "Ann", "ann@example.com", "Hi");

        assertEquals(MailSendResult.Status.NOT_CONFIGURED, result.status());
        verify(transport, never()).send(any());
    }

    @Test
    void aSmtpTestSendsToTheGivenAddress() throws Exception {
        MailTransport transport = mock(MailTransport.class);
        ContactMailer mailer = new ContactMailer(settings(RELAY), transport);

        MailSendResult result = mailer.sendTest("acme", "Acme", "check@example.com");

        assertEquals(MailSendResult.Status.SENT, result.status());
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(captor.capture());
        assertEquals("check@example.com", ((jakarta.mail.internet.InternetAddress)
                captor.getValue().getRecipients(Message.RecipientType.TO)[0]).getAddress());
    }

    @Test
    void thePasswordIsTheSitesSecretInClearAndReachesTheRelayCredentials() throws Exception {
        MailTransport transport = mock(MailTransport.class);
        ContactMailer mailer = new ContactMailer(settings(RELAY), transport);

        SmtpSettings smtp = mailer.settingsFor("acme");
        assertEquals("s3cret", smtp.password(), "PluginSettings.secret supplies the password, already in clear");
        mailer.send("acme", "Acme", "Ann", "ann@example.com", "Hi");
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(captor.capture());
        PasswordAuthentication auth = credentials(captor.getValue().getSession());
        assertEquals("postmaster", auth.getUserName());
        assertEquals("s3cret", auth.getPassword(), "the relay is offered the secret's clear value");
    }

    @Test
    void aPasswordFoundInTheSettingsViewIsNeverUsed() {
        // The kernel leaves secret keys out of the view; a value there anyway (a hand-built view, a future change)
        // must not become the password: only the declared secret, read through secret(), is trusted.
        Map<String, Object> withLeak = new HashMap<>(RELAY);
        withLeak.put("smtpPassword", "ENC(ciphertext-not-a-password)");
        ContactMailer viewOnly = new ContactMailer(new FakePluginSettings().site("acme", withLeak),
                mock(MailTransport.class));
        assertEquals("", viewOnly.settingsFor("acme").password(), "no secret ⇒ no password, whatever the view holds");

        ContactMailer both = new ContactMailer(settings(withLeak), mock(MailTransport.class));
        assertEquals("s3cret", both.settingsFor("acme").password(), "the secret wins over anything in the view");
    }

    @Test
    void theSecretIsReadForTheSendingSiteOnly() {
        FakePluginSettings settings = settings(RELAY).site("other", RELAY).secret("other", "smtpPassword", "other-pw");
        ContactMailer mailer = new ContactMailer(settings, mock(MailTransport.class));
        assertEquals("s3cret", mailer.settingsFor("acme").password());
        assertEquals("other-pw", mailer.settingsFor("other").password(), "each site authenticates with its own secret");
        assertEquals("", mailer.settingsFor("nobody").password(), "a site with no secret has no password");
    }

    @Test
    void thePasswordNeverAppearsInTheSettingsText() {
        String text = new ContactMailer(settings(RELAY), mock(MailTransport.class)).settingsFor("acme").toString();
        assertFalse(text.contains("s3cret"), "masked in logs: " + text);
        assertTrue(text.contains("password=<set>"));
    }

    @Test
    void settingsAreReadTypedWithTheDescriptorDefaultsAndAClampedPort() {
        Map<String, Object> odd = new HashMap<>(RELAY);
        odd.put("smtpPort", 99999L);
        odd.put("smtpHost", "  mail.example.com  ");
        SmtpSettings smtp = new ContactMailer(settings(odd), mock(MailTransport.class)).settingsFor("acme");
        assertEquals(65535, smtp.port(), "the port is clamped to 1..65535");
        assertEquals("mail.example.com", smtp.host(), "values are stripped");
        SmtpSettings bare = new ContactMailer(new FakePluginSettings(), mock(MailTransport.class)).settingsFor("acme");
        assertEquals(25, bare.port(), "port 25 when unset");
        assertFalse(bare.auth());
        assertFalse(bare.isConfigured(), "no host and no recipient");
    }

    @Test
    void implicitTlsRidesSslEnableOnTheSmtpProtocol() {
        // Regression: implicit TLS (SMTPS, e.g. 465) must ride mail.smtp.ssl.enable on the smtp protocol. Switching
        // to "smtps" left mail.smtp.host unset, so the static Transport.send() (which maps rfc822 -> smtp, ignoring
        // mail.transport.protocol) dialed localhost:25 and failed with "Connection refused".
        jakarta.mail.Session s = ContactMailer.session(new SmtpSettings(
                "mail.example.com", 465, true, "postmaster", "pw", false, true, "site@example.com", "owner@example.com"));

        assertEquals("smtp", s.getProperty("mail.transport.protocol"), "protocol stays smtp (address map ignores smtps)");
        assertEquals("mail.example.com", s.getProperty("mail.smtp.host"), "host on mail.smtp.* (what the send reads)");
        assertEquals("465", s.getProperty("mail.smtp.port"));
        assertEquals("true", s.getProperty("mail.smtp.ssl.enable"), "implicit TLS on connect");
        assertNull(s.getProperty("mail.smtps.host"), "no smtps-scoped host the static send would silently ignore");
        assertNull(s.getProperty("mail.smtp.starttls.enable"), "implicit TLS and STARTTLS are mutually exclusive");
    }

    @Test
    void starttlsRidesStarttlsEnableOnTheSmtpProtocol() {
        jakarta.mail.Session s = ContactMailer.session(new SmtpSettings(
                "mail.example.com", 587, false, null, null, true, false, "site@example.com", "owner@example.com"));

        assertEquals("smtp", s.getProperty("mail.transport.protocol"));
        assertEquals("587", s.getProperty("mail.smtp.port"));
        assertEquals("true", s.getProperty("mail.smtp.starttls.enable"), "opportunistic STARTTLS");
        assertNull(s.getProperty("mail.smtp.ssl.enable"), "STARTTLS mode must not also enable implicit TLS");
    }
}
