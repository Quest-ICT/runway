package mx.quest.runway.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import mx.quest.runway.security.RunwaySecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Property/fuzz coverage of the real secret cipher (AES-256-GCM on the JDK provider), exercising the
 * production {@link AesGcmSecretCipher} — not a stub. Asserts the crypto invariants over random inputs: a round-trip
 * is lossless; encryption is non-deterministic (a fresh GCM nonce each call); a tampered or malformed
 * {@code ENC(...)} value fails <b>closed</b> (a clean exception, never a wrong plaintext or a hang). Uses a
 * high-entropy fixed test key and a seeded {@link Random} for reproducibility.
 */
class SecretCipherFuzzTest {

    private static final int ITERATIONS = 3_000;
    private SecretCipher cipher;

    @BeforeEach
    void setUp() {
        RunwaySecurityProperties props = new RunwaySecurityProperties();
        // A long, high-entropy key — the entropy the SHA-256 KDF assumes (see RunwaySecurityProperties).
        props.setSecretKey("fuzz-test-key-8f3a1c9e2b7d4056a1e8c3f9b2d6047a-e91c7b53a08f2d16");
        cipher = new AesGcmSecretCipher(props);
        assertTrue(cipher.isAvailable(), "a configured key arms the cipher");
    }

    @Test
    void roundTripIsLosslessForArbitraryPlaintext() {
        Random rnd = new Random(0x5EC2E7L);
        for (int i = 0; i < ITERATIONS; i++) {
            String plaintext = randomText(rnd);
            String enc = cipher.encrypt(plaintext);
            assertTrue(cipher.isEncrypted(enc), "ciphertext carries the ENC(...) tag: " + enc);
            assertEquals(plaintext, cipher.decrypt(enc), "round-trip is lossless");
        }
    }

    @Test
    void encryptionIsNonDeterministic() {
        Random rnd = new Random(0x901CEL);
        for (int i = 0; i < 500; i++) {
            String plaintext = randomText(rnd);
            String a = cipher.encrypt(plaintext);
            String b = cipher.encrypt(plaintext);
            assertNotEquals(a, b, "a fresh nonce per call → different ciphertext for the same plaintext");
            assertEquals(plaintext, cipher.decrypt(a));
            assertEquals(plaintext, cipher.decrypt(b));
        }
    }

    @Test
    void aTamperedCiphertextFailsClosed() {
        Random rnd = new Random(0x7A11BEDL);
        for (int i = 0; i < 1_000; i++) {
            String enc = cipher.encrypt(randomText(rnd));
            String body = enc.substring(4, enc.length() - 1);          // strip ENC( ... )
            // Tamper at the BYTE level (base64's tail bits alias, so a char-flip isn't always a real change):
            // decode, flip one bit anywhere in IV|ciphertext|tag, re-encode. Any real change fails GCM auth.
            byte[] raw = java.util.Base64.getDecoder().decode(body);
            raw[rnd.nextInt(raw.length)] ^= (byte) (1 << rnd.nextInt(8));
            String tampered = "ENC(" + java.util.Base64.getEncoder().encodeToString(raw) + ")";
            assertThrows(RuntimeException.class, () -> cipher.decrypt(tampered),
                    "GCM authentication must reject a byte-tampered ciphertext");
        }
    }

    @Test
    void garbageInsideTheTagFailsClosedAndNonTaggedIsPassThrough() {
        Random rnd = new Random(0x6A46A6EL);
        for (int i = 0; i < 1_000; i++) {
            String garbage = randomText(rnd);
            // Wrapped in the tag → treated as ciphertext → must throw, never return a plaintext.
            assertThrows(RuntimeException.class, () -> cipher.decrypt("ENC(" + garbage.replace(")", "") + ")"));
            // Not tagged → treated as already-plaintext → returned unchanged (the hand-written-secret path).
            String untagged = garbage.replace("ENC(", "x");
            if (!cipher.isEncrypted(untagged)) {
                assertEquals(untagged, cipher.decrypt(untagged), "a non-tagged value is passed through");
            }
        }
    }

    /** A random string: empty, unicode, control chars, and long runs all appear across the corpus. */
    private static String randomText(Random rnd) {
        int len = rnd.nextInt(64);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) rnd.nextInt(0x2FFF)); // spans ASCII, controls, Latin, CJK-ish
        }
        return sb.toString();
    }
}
