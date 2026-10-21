package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** The shared Argon2id factory: round-trip, the house parameters, and PHC self-description across profiles. */
class PasswordEncodersTest {

    @Test
    void hashRoundTrips() {
        Argon2PasswordEncoder encoder = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
        String hash = encoder.encode("correct horse");
        assertTrue(encoder.matches("correct horse", hash));
        assertFalse(encoder.matches("wrong horse", hash));
    }

    @Test
    void standardCarriesTheHouseParameters() {
        // Guards the (saltLen, hashLen, PARALLELISM, memoryKiB, ITERATIONS) argument order: a
        // parallelism/iterations transposition would show as p=2,t=1 here.
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("pw");
        assertTrue(hash.contains("$argon2id$"), hash);
        assertTrue(hash.contains("m=19456,t=2,p=1"), hash);
    }

    @Test
    void highRaisesOnlyTheMemoryCost() {
        String hash = PasswordEncoders.argon2id(PasswordStrength.HIGH).encode("pw");
        assertTrue(hash.contains("m=32768,t=2,p=1"), hash);
    }

    @Test
    void profileChangeNeverBreaksExistingHashes() {
        // PHC strings are self-describing, so switching the active profile only affects new hashes.
        Argon2PasswordEncoder standard = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
        Argon2PasswordEncoder high = PasswordEncoders.argon2id(PasswordStrength.HIGH);

        assertTrue(high.matches("pw", standard.encode("pw")), "HIGH encoder verifies a STANDARD hash");
        assertTrue(standard.matches("pw", high.encode("pw")), "STANDARD encoder verifies a HIGH hash");
    }
}
