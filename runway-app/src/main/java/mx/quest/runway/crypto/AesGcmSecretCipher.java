package mx.quest.runway.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import mx.quest.runway.security.RunwaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The production {@link SecretCipher}: AES-256-GCM on the JDK's own provider, with no third-party library.
 *
 * <p><b>Key.</b> The AES-256 key is the SHA-256 digest of {@code runway.security.secret-key} (its UTF-8 bytes).
 * The digest is a fixed-length mapping, not a password-hardening function, so the setting must itself be a
 * long random string (see {@link RunwaySecurityProperties}). If the setting is blank the cipher reports
 * {@link #isAvailable()} false and refuses to encrypt, rather than silently using an empty key.
 *
 * <p><b>Stored form.</b> {@code ENC(} + Base64 (standard alphabet, padded) of {@code nonce || ciphertext || tag}
 * + {@code )}, where the nonce is 12 random bytes, fresh for every call, and the tag is the full 128-bit GCM
 * tag. No associated data is bound. This is the form every earlier Runway release wrote, so secrets already on
 * disk keep decrypting; {@code AesGcmSecretCipherTest} pins it with vectors produced by those releases.
 *
 * <p><b>Failures.</b> A tagged value that is not Base64, or too short to hold a nonce and a tag, is rejected with
 * {@link IllegalArgumentException}; a wrong key or any tampering fails GCM authentication and is rejected with
 * {@link IllegalStateException}. Neither ever yields a partial or wrong plaintext.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(AesGcmSecretCipher.class);

    static final String PREFIX = "ENC(";
    static final String SUFFIX = ")";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The AES-256 key, or {@code null} when {@code runway.security.secret-key} is not usable. */
    private final SecretKey key;

    public AesGcmSecretCipher(RunwaySecurityProperties security) {
        String passphrase = security.getSecretKey();
        if (passphrase == null || passphrase.isBlank()) {
            key = null;
            log.info("Secret cipher unavailable — runway.security.secret-key is not configured; "
                    + "plugin secret settings cannot be encrypted until it is set.");
        } else if (isTagged(passphrase)) {
            // An ENC(...) value here would be a key encrypted under some other key, which this cipher has no
            // way to resolve. Deriving a key from the literal text would silently fail every stored secret, so
            // refuse instead and say why.
            key = null;
            log.error("Secret cipher unavailable — runway.security.secret-key is itself in the ENC(...) form. "
                    + "Set it to the plain passphrase; encrypted secrets cannot be read until it is.");
        } else {
            key = deriveKey(passphrase);
            log.info("Secret cipher armed (runway.security.secret-key configured).");
        }
    }

    @Override
    public boolean isAvailable() {
        return key != null;
    }

    @Override
    public String encrypt(String plaintext) {
        if (key == null) {
            throw new IllegalStateException("no secret key configured (runway.security.secret-key)");
        }
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is null");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out) + SUFFIX;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    @Override
    public String decrypt(String value) {
        // A non-tagged value is plaintext already (or null); without a key we cannot decrypt, so pass through.
        if (value == null || key == null || !isTagged(value)) {
            return value;
        }
        String body = value.substring(PREFIX.length(), value.length() - SUFFIX.length());
        byte[] in;
        try {
            in = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("encrypted value is not valid Base64", e);
        }
        if (in.length < NONCE_BYTES + TAG_BYTES) {
            throw new IllegalArgumentException("encrypted value is too short to hold a nonce and a GCM tag");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
            String text = new String(plain, StandardCharsets.UTF_8);
            Arrays.fill(plain, (byte) 0);
            return text;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed (wrong key or corrupted value)", e);
        }
    }

    @Override
    public boolean isEncrypted(String value) {
        return isTagged(value);
    }

    private static boolean isTagged(String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith(SUFFIX);
    }

    private static SecretKey deriveKey(String passphrase) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(passphrase.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
