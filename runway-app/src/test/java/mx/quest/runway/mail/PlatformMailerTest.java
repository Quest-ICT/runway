package mx.quest.runway.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The platform mailer's build-and-send seam, exercised against a mock {@link MailTransport} (no socket): the
 * MIME message is addressed and bodied from the platform SMTP block; {@link PlatformMailer#isConfigured()}
 * reflects host+from; an unconfigured account refuses to send; and the auth path decrypts the stored secret
 * (never sending the {@code ENC(...)} ciphertext as the password).
 */
class PlatformMailerTest {

    /** A reversible test cipher: encrypt wraps base64 in {@code ENC(...)}, decrypt unwraps it; records decrypts. */
    private static class RecordingCipher implements SecretCipher {
        String lastDecryptedInput;

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public String encrypt(String plaintext) {
            return "ENC(" + Base64.getEncoder().encodeToString(plaintext.getBytes()) + ")";
        }

        @Override public String decrypt(String value) {
            lastDecryptedInput = value;
            if (!isEncrypted(value)) {
                return value;
            }
            return new String(Base64.getDecoder().decode(value.substring(4, value.length() - 1)));
        }

        @Override public boolean isEncrypted(String value) {
            return value != null && value.startsWith("ENC(") && value.endsWith(")");
        }
    }

    /** Captures the message rather than opening a socket. */
    private static final class CapturingTransport implements MailTransport {
        final AtomicReference<MimeMessage> last = new AtomicReference<>();
        int count;

        @Override public void send(MimeMessage message) {
            last.set(message);
            count++;
        }
    }

    private static PlatformSettingsStore store(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        return new PlatformSettingsStore(StorageLayout.from(props), new YamlStore());
    }

    @Test
    void isConfiguredReflectsHostAndFrom(@TempDir Path root) {
        PlatformSettingsStore settings = store(root);
        PlatformMailer mailer = new PlatformMailer(settings, new RecordingCipher(), new CapturingTransport());
        assertFalse(mailer.isConfigured(), "no smtp block ⇒ not configured");

        settings.update(previous -> PlatformSettings.EMPTY.withSmtp(
                new PlatformSettings.Smtp("smtp.example.com", 587, false, null, null, "", false, false)));
        assertFalse(mailer.isConfigured(), "host but blank from ⇒ not configured");

        settings.update(previous -> PlatformSettings.EMPTY.withSmtp(
                new PlatformSettings.Smtp("smtp.example.com", 587, false, null, null, "noreply@example.com", true, false)));
        assertTrue(mailer.isConfigured(), "host + from ⇒ configured");
    }

    @Test
    void sendBuildsAndDispatchesTheMessage(@TempDir Path root) throws Exception {
        PlatformSettingsStore settings = store(root);
        settings.update(previous -> PlatformSettings.EMPTY.withSmtp(
                new PlatformSettings.Smtp("smtp.example.com", 587, false, null, null, "noreply@example.com", true, false)));
        CapturingTransport transport = new CapturingTransport();
        PlatformMailer mailer = new PlatformMailer(settings, new RecordingCipher(), transport);

        mailer.send("user@example.org", "Reset your password", "Click the link.");

        assertEquals(1, transport.count, "the message was handed to the transport once");
        MimeMessage msg = transport.last.get();
        assertEquals("noreply@example.com", msg.getFrom()[0].toString());
        assertEquals("user@example.org", msg.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("Reset your password", msg.getSubject());
        assertEquals("Click the link.", msg.getContent());
    }

    @Test
    void anUnconfiguredAccountRefusesToSend(@TempDir Path root) {
        PlatformSettingsStore settings = store(root);
        CapturingTransport transport = new CapturingTransport();
        PlatformMailer mailer = new PlatformMailer(settings, new RecordingCipher(), transport);

        assertThrows(MessagingException.class, () -> mailer.send("a@b.com", "s", "b"));
        assertEquals(0, transport.count, "nothing is sent when the platform account is not configured");
    }

    @Test
    void anUndecryptablePasswordFailsAsAMailError(@TempDir Path root) {
        PlatformSettingsStore settings = store(root);
        settings.update(previous -> PlatformSettings.EMPTY.withSmtp(new PlatformSettings.Smtp(
                "smtp.example.com", 587, true, "postmaster", "ENC(stored-under-another-key)", "noreply@example.com",
                true, false)));
        SecretCipher wrongKey = new RecordingCipher() {
            @Override public String decrypt(String value) {
                throw new IllegalStateException("decryption failed (wrong key or corrupted value)");
            }
        };
        CapturingTransport transport = new CapturingTransport();
        PlatformMailer mailer = new PlatformMailer(settings, wrongKey, transport);

        // Callers handle MessagingException (recovery stays non-enumerating, the test-send shows an error);
        // an IllegalStateException would escape them.
        MessagingException e = assertThrows(MessagingException.class, () -> mailer.send("user@example.org", "s", "b"));
        assertTrue(e.getMessage().contains("cannot be decrypted"));
        assertEquals(0, transport.count, "nothing was sent");
    }

    @Test
    void theAuthPasswordIsDecryptedNotSentAsCiphertext(@TempDir Path root) throws MessagingException {
        PlatformSettingsStore settings = store(root);
        RecordingCipher cipher = new RecordingCipher();
        String stored = cipher.encrypt("hunter2");
        settings.update(previous -> PlatformSettings.EMPTY.withSmtp(new PlatformSettings.Smtp(
                "smtp.example.com", 587, true, "postmaster", stored, "noreply@example.com", true, false)));
        PlatformMailer mailer = new PlatformMailer(settings, cipher, new CapturingTransport());

        mailer.send("user@example.org", "s", "b");

        // The mailer decrypted the stored ENC(...) secret at send time (rather than using the ciphertext).
        assertEquals(stored, cipher.lastDecryptedInput, "the stored ciphertext was the decrypt input");
        assertTrue(cipher.isEncrypted(stored), "the stored secret is ENC(...)-encrypted, not plaintext");
    }

    @Test
    void implicitTlsRidesSslEnableOnTheSmtpProtocol(@TempDir Path root) throws MessagingException {
        // Regression: implicit TLS (SMTPS, e.g. 465) must NOT switch the transport protocol to "smtps". The static
        // Transport.send() resolves an rfc822 recipient to the "smtp" transport via the address map (ignoring
        // mail.transport.protocol); a "smtps" value left mail.smtp.host unset, so the send dialed localhost:25 and
        // failed with "Connection refused". Implicit TLS must ride mail.smtp.ssl.enable on the smtp protocol.
        RecordingCipher cipher = new RecordingCipher();
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport());
        Session s = mailer.session(new PlatformSettings.Smtp(
                "mail.example.com", 465, true, "postmaster", cipher.encrypt("pw"), "noreply@example.com", false, true));

        assertEquals("smtp", s.getProperty("mail.transport.protocol"), "protocol stays smtp (address map ignores smtps)");
        assertEquals("mail.example.com", s.getProperty("mail.smtp.host"), "host is set on mail.smtp.* (what the send reads)");
        assertEquals("465", s.getProperty("mail.smtp.port"));
        assertEquals("true", s.getProperty("mail.smtp.auth"));
        assertEquals("true", s.getProperty("mail.smtp.ssl.enable"), "implicit TLS on connect");
        assertNull(s.getProperty("mail.smtps.host"), "no smtps-scoped host the static send would silently ignore");
        assertNull(s.getProperty("mail.smtp.starttls.enable"), "implicit TLS and STARTTLS are mutually exclusive");
    }

    @Test
    void starttlsRidesStarttlsEnableOnTheSmtpProtocol(@TempDir Path root) throws MessagingException {
        PlatformMailer mailer = new PlatformMailer(store(root), new RecordingCipher(), new CapturingTransport());
        Session s = mailer.session(new PlatformSettings.Smtp(
                "mail.example.com", 587, false, null, null, "noreply@example.com", true, false));

        assertEquals("smtp", s.getProperty("mail.transport.protocol"));
        assertEquals("587", s.getProperty("mail.smtp.port"));
        assertEquals("true", s.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", s.getProperty("mail.smtp.starttls.required"),
                "a relay that does not offer STARTTLS fails the send instead of getting the credentials in clear");
        assertEquals("true", s.getProperty("mail.smtp.ssl.checkserveridentity"), "the certificate names the relay");
        assertNull(s.getProperty("mail.smtp.ssl.enable"), "STARTTLS mode must not also enable implicit TLS");
    }

    @Test
    void everySessionHasBoundedWaitsAndImplicitTlsChecksTheServerName(@TempDir Path root) throws MessagingException {
        RecordingCipher cipher = new RecordingCipher();
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport());
        for (PlatformSettings.Smtp smtp : java.util.List.of(
                new PlatformSettings.Smtp("m.example.com", 25, false, null, null, "n@example.com", false, false),
                new PlatformSettings.Smtp("m.example.com", 587, false, null, null, "n@example.com", true, false),
                new PlatformSettings.Smtp("m.example.com", 465, true, "u", cipher.encrypt("pw"), "n@example.com",
                        false, true))) {
            Session s = mailer.session(smtp);
            assertEquals("10000", s.getProperty("mail.smtp.connectiontimeout"), smtp.toString());
            assertEquals("30000", s.getProperty("mail.smtp.timeout"), smtp.toString());
            assertEquals("30000", s.getProperty("mail.smtp.writetimeout"), smtp.toString());
        }
        Session implicit = mailer.session(new PlatformSettings.Smtp(
                "m.example.com", 465, false, null, null, "n@example.com", false, true));
        assertEquals("true", implicit.getProperty("mail.smtp.ssl.checkserveridentity"));
        Session plain = mailer.session(new PlatformSettings.Smtp(
                "m.example.com", 25, false, null, null, "n@example.com", false, false));
        assertEquals("false", plain.getProperty("mail.smtp.starttls.enable"), "plain says so explicitly");
        assertNull(plain.getProperty("mail.smtp.starttls.required"));
    }
}
