package mx.quest.runway.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
        PlatformMailer mailer = new PlatformMailer(settings, new RecordingCipher(), new CapturingTransport(), new OAuth2TokenClient());
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
        PlatformMailer mailer = new PlatformMailer(settings, new RecordingCipher(), transport, new OAuth2TokenClient());

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
        PlatformMailer mailer = new PlatformMailer(settings, new RecordingCipher(), transport, new OAuth2TokenClient());

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
        PlatformMailer mailer = new PlatformMailer(settings, wrongKey, transport, new OAuth2TokenClient());

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
        PlatformMailer mailer = new PlatformMailer(settings, cipher, new CapturingTransport(), new OAuth2TokenClient());

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
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport(), new OAuth2TokenClient());
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
        PlatformMailer mailer = new PlatformMailer(store(root), new RecordingCipher(), new CapturingTransport(), new OAuth2TokenClient());
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
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport(), new OAuth2TokenClient());
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

    /** A token client that hands out a fixed token and counts what it was asked, with no HTTP. */
    private static final class FixedTokens extends OAuth2TokenClient {
        final List<Request> asked = new ArrayList<>();
        int forgotten;

        FixedTokens() {
            super(null, java.time.Clock.systemUTC());
        }

        @Override public synchronized String token(Request request) {
            asked.add(request);
            return "eyJ0eXAi.token";
        }

        @Override public synchronized void forget() {
            forgotten++;
        }
    }

    private static PlatformSettings.Smtp oauth(RecordingCipher cipher, String username, boolean starttls) {
        return new PlatformSettings.Smtp("smtp.office365.com", 587, true, username, null, "noreply@example.com",
                starttls, false, PlatformSettings.Smtp.AUTH_OAUTH2,
                "https://login.microsoftonline.com/tenant-1/oauth2/v2.0/token", "client-1",
                cipher.encrypt("client-secret"), "https://outlook.office365.com/.default");
    }

    private static PasswordAuthentication credentials(Session session) {
        return session.requestPasswordAuthentication(null, 587, "smtp", null, null);
    }

    @Test
    void anOAuth2AccountSignsInWithXoauth2AndTheTokenAsThePassword(@TempDir Path root) throws MessagingException {
        RecordingCipher cipher = new RecordingCipher();
        FixedTokens tokens = new FixedTokens();
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport(), tokens);

        Session s = mailer.session(oauth(cipher, "mailbox@example.com", true));

        assertEquals("XOAUTH2", s.getProperty("mail.smtp.auth.mechanisms"),
                "only XOAUTH2: a server without it must not receive the token as a PLAIN password");
        assertEquals("true", s.getProperty("mail.smtp.auth"));
        assertEquals("true", s.getProperty("mail.smtp.starttls.required"));
        PasswordAuthentication pa = credentials(s);
        assertEquals("mailbox@example.com", pa.getUserName());
        assertEquals("eyJ0eXAi.token", pa.getPassword());
        assertEquals(List.of(new OAuth2TokenClient.Request(
                "https://login.microsoftonline.com/tenant-1/oauth2/v2.0/token", "client-1", "client-secret",
                "https://outlook.office365.com/.default")), tokens.asked, "the client secret was decrypted first");
    }

    @Test
    void anOAuth2AccountWithNoUsernameSendsAsTheFromMailbox(@TempDir Path root) throws MessagingException {
        RecordingCipher cipher = new RecordingCipher();
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport(), new FixedTokens());

        assertEquals("noreply@example.com", credentials(mailer.session(oauth(cipher, " ", true))).getUserName());
    }

    @Test
    void anOAuth2AccountWithoutTlsIsRefusedBeforeAnyTokenIsFetched(@TempDir Path root) {
        RecordingCipher cipher = new RecordingCipher();
        FixedTokens tokens = new FixedTokens();
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport(), tokens);

        MessagingException e = assertThrows(MessagingException.class,
                () -> mailer.session(oauth(cipher, "m@example.com", false)));
        assertTrue(e.getMessage().contains("STARTTLS or implicit TLS"), e.getMessage());
        assertTrue(tokens.asked.isEmpty());
    }

    @Test
    void aPasswordAccountNamesNoMechanism(@TempDir Path root) throws MessagingException {
        RecordingCipher cipher = new RecordingCipher();
        FixedTokens tokens = new FixedTokens();
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport(), tokens);
        // The OAuth2 fields are ignored while the method is "password" (switching back keeps them stored).
        PlatformSettings.Smtp o = oauth(cipher, "u", true);
        Session s = mailer.session(new PlatformSettings.Smtp(o.host(), o.port(), true, "u", cipher.encrypt("pw"),
                o.from(), true, false, PlatformSettings.Smtp.AUTH_PASSWORD, o.oauthTokenUrl(), o.oauthClientId(),
                o.oauthClientSecret(), o.oauthScope()));

        assertNull(s.getProperty("mail.smtp.auth.mechanisms"));
        assertEquals("pw", credentials(s).getPassword());
        assertTrue(tokens.asked.isEmpty());
    }

    @Test
    void aRefusedTokenIsForgottenSoTheNextSendAsksForANewOne(@TempDir Path root) {
        RecordingCipher cipher = new RecordingCipher();
        PlatformSettingsStore settings = store(root);
        settings.update(previous -> PlatformSettings.EMPTY.withSmtp(oauth(cipher, "m@example.com", true)));
        FixedTokens tokens = new FixedTokens();
        MailTransport refusing = message -> {
            throw new AuthenticationFailedException("535 5.7.3 Authentication unsuccessful");
        };
        PlatformMailer mailer = new PlatformMailer(settings, cipher, refusing, tokens);

        assertThrows(AuthenticationFailedException.class, () -> mailer.send("u@example.org", "s", "b"));
        assertEquals(1, tokens.forgotten);
    }

    @Test
    void theXoauth2ExchangeOnTheWireCarriesTheMailboxAndTheBearerToken(@TempDir Path root) throws Exception {
        // What the mailer configures, sent by the bundled Angus Mail to a scripted SMTP server: proves that "the token
        // as the password, with mail.smtp.auth.mechanisms=XOAUTH2" yields RFC-shaped XOAUTH2. Only TLS is switched
        // off for the test server; the mailer itself refuses OAuth2 without TLS.
        RecordingCipher cipher = new RecordingCipher();
        PlatformMailer mailer = new PlatformMailer(store(root), cipher, new CapturingTransport(), new FixedTokens());
        Session configured = mailer.session(oauth(cipher, "mailbox@example.com", true));
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            java.util.concurrent.CompletableFuture<List<String>> dialogue =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> scriptedSmtp(listener));
            java.util.Properties props = new java.util.Properties();
            props.putAll(configured.getProperties());
            props.put("mail.smtp.host", "127.0.0.1");
            props.put("mail.smtp.port", String.valueOf(listener.getLocalPort()));
            props.put("mail.smtp.starttls.enable", "false");
            props.put("mail.smtp.starttls.required", "false");
            PasswordAuthentication pa = credentials(configured);
            Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return pa;
                }
            });
            MimeMessage message = mailer.build(oauth(cipher, "mailbox@example.com", true), "u@example.org", "s", "b");
            jakarta.mail.Transport transport = session.getTransport("smtp");
            transport.connect();
            transport.sendMessage(message, message.getAllRecipients());
            transport.close();

            List<String> lines = dialogue.get(10, java.util.concurrent.TimeUnit.SECONDS);
            String auth = lines.stream().filter(l -> l.startsWith("AUTH ")).findFirst().orElseThrow();
            String[] parts = auth.split(" ");
            assertEquals("XOAUTH2", parts[1], auth);
            assertEquals("user=mailbox@example.com\u0001auth=Bearer eyJ0eXAi.token\u0001\u0001",
                    new String(Base64.getDecoder().decode(parts[2]), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(lines.contains("MAIL FROM:<noreply@example.com>"), lines.toString());
        }
    }

    /** A one-connection SMTP server offering AUTH XOAUTH2 that accepts anything; returns the client's lines. */
    private static List<String> scriptedSmtp(java.net.ServerSocket listener) {
        List<String> lines = new ArrayList<>();
        try (java.net.Socket socket = listener.accept()) {
            socket.setSoTimeout(10_000);
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
            java.io.Writer out = new java.io.OutputStreamWriter(socket.getOutputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII);
            out.write("220 test ESMTP\r\n");
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
                String verb = line.length() >= 4 ? line.substring(0, 4).toUpperCase(java.util.Locale.ROOT) : line;
                switch (verb) {
                    case "EHLO" -> out.write("250-test\r\n250-AUTH XOAUTH2 PLAIN LOGIN\r\n250 OK\r\n");
                    case "AUTH" -> out.write("235 2.7.0 Authentication successful\r\n");
                    case "DATA" -> {
                        out.write("354 go\r\n");
                        out.flush();
                        String body;
                        while ((body = in.readLine()) != null && !body.equals(".")) {
                            // the message itself is not checked here
                        }
                        out.write("250 queued\r\n");
                    }
                    case "QUIT" -> {
                        out.write("221 bye\r\n");
                        out.flush();
                        return lines;
                    }
                    default -> out.write("250 OK\r\n");
                }
                out.flush();
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return lines;
    }
}
