package mx.quest.runway.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.security.RunwaySecurityProperties;
import org.junit.jupiter.api.Test;

/**
 * The kernel cipher: tagged {@code ENC(...)} round-trip, non-deterministic ciphertext (GCM), plaintext
 * pass-through on decrypt, the unavailable-without-a-key contract, and compatibility with the stored form that
 * earlier releases wrote.
 */
class AesGcmSecretCipherTest {

    private static AesGcmSecretCipher withKey(String key) {
        RunwaySecurityProperties props = new RunwaySecurityProperties();
        props.setSecretKey(key);
        return new AesGcmSecretCipher(props);
    }

    @Test
    void roundTripsThroughTheTaggedForm() {
        AesGcmSecretCipher cipher = withKey("a-test-master-key-please-change-32b");
        assertTrue(cipher.isAvailable());

        String encrypted = cipher.encrypt("super-secret-value");
        assertTrue(cipher.isEncrypted(encrypted), "stored form is ENC(...)-tagged");
        assertTrue(encrypted.startsWith("ENC(") && encrypted.endsWith(")"));
        assertFalse(encrypted.contains("super-secret-value"), "the plaintext never appears in the stored form");
        assertEquals("super-secret-value", cipher.decrypt(encrypted));
    }

    @Test
    void ciphertextIsNonDeterministic() {
        AesGcmSecretCipher cipher = withKey("a-test-master-key-please-change-32b");
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"), "a fresh GCM nonce each time");
    }

    @Test
    void decryptPassesANonTaggedValueThrough() {
        AesGcmSecretCipher cipher = withKey("a-test-master-key-please-change-32b");
        assertEquals("not-encrypted", cipher.decrypt("not-encrypted"));
        assertFalse(cipher.isEncrypted("not-encrypted"));
    }

    @Test
    void aBlankKeyLeavesTheCipherUnavailable() {
        AesGcmSecretCipher cipher = withKey("   ");
        assertFalse(cipher.isAvailable());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("x"));
    }

    /** The passphrase the compatibility vectors below were encrypted under. */
    private static final String VECTOR_KEY = "compat-vector-passphrase";

    /**
     * Values written by earlier releases (which used an external library for the same AES-256-GCM format),
     * kept verbatim: if any of these stops decrypting, secrets already stored on installations would too.
     */
    @Test
    void decryptsValuesWrittenByEarlierReleases() {
        AesGcmSecretCipher cipher = withKey(VECTOR_KEY);
        assertEquals("", cipher.decrypt("ENC(laMXBC47EwjoepFMcOYvDQvNCdx37WRaZzF9Rg==)"));
        assertEquals("s3cret", cipher.decrypt("ENC(xfyQOzW7NLhGRBCDJvkiD8dXmtiFlFXZeJrl7rWxQYFiEg==)"));
        assertEquals("contrase\u00f1a \u00f1 \u2014 \u65e5\u672c\u8a9e \ud83d\udd11",
                cipher.decrypt("ENC(6cVnPKJSlLDuPl5KJBMXHIm/fp+257fRQbck9qjJGfBAcKK+lrOoW0s7G1YN4LQ908h3aQBTCnE+OG5d8Q==)"));
        assertEquals("x".repeat(300), cipher.decrypt(
                "ENC(BykEOxKF6iUhzQ7w/AEpw/Oovl5fIU+xToMn9KE+EyQNxota0cCfpBs6zHRYVH6NPdhSGGxp78tudUKqSlQMdbXCznzHHvNu7hiihR9bn8B7JBtHfrHbcBSZwpvUS/OVJ/yjfNr7NjOQiut4+YS1NeWa7e5zYAfPXI7AcnjaD1fvlBZA7M6sEQ1pwYq3MhkwUXKFxtmP/g8x1U1yMG71t6p7lf/uZ0FOj1U++J21VEIFjkK2uQ6/J4qlTobwojOjdQi9zz8vBHVkxBi6sX6A8HySKEQpUtAFICopnmmJ1M0c2jxUNKbtK3cu7EIzRq3qerZV6bS8dpuUzVQoP9QO6odFYHkYU/n6XCpUAFp+1Y46d15eDth4SCS8M/Ma4i23Nk6+uO3OaYPFR+voRR9Xxqv6AxrrFbOFnYVFI7rWq4EW2b8P0le6fQ==)"));
    }

    @Test
    void aWrongKeyFailsClosedOnAnEarlierReleaseValue() {
        AesGcmSecretCipher cipher = withKey("some-other-passphrase");
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("ENC(xfyQOzW7NLhGRBCDJvkiD8dXmtiFlFXZeJrl7rWxQYFiEg==)"));
    }

    @Test
    void anEncryptedLookingKeyIsRefusedRatherThanUsedLiterally() {
        AesGcmSecretCipher cipher = withKey("ENC(AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA)");
        assertFalse(cipher.isAvailable());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("x"));
    }

    @Test
    void malformedTaggedValuesAreRejectedAsArguments() {
        AesGcmSecretCipher cipher = withKey(VECTOR_KEY);
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("ENC(not base64!)"));
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("ENC(AAAA)"));
    }

    @Test
    void theStoredFormIsNoncePlusCiphertextPlusTag() {
        AesGcmSecretCipher cipher = withKey(VECTOR_KEY);
        String enc = cipher.encrypt("abc");
        byte[] raw = java.util.Base64.getDecoder().decode(enc.substring(4, enc.length() - 1));
        assertEquals(12 + 3 + 16, raw.length, "12-byte nonce, 3 plaintext bytes, 16-byte tag");
    }
}
