package mx.quest.runway.security;

/**
 * Thrown when a password-hashing slot could not be acquired before the timeout — the host is under
 * a concurrent-hashing flood. The authentication layer maps this to a transient "busy, retry"
 * failure (not a credential rejection), so a deliberate Argon2id login flood degrades gracefully
 * instead of exhausting memory.
 */
public class PasswordHashingBusyException extends RuntimeException {

    public PasswordHashingBusyException(String message) {
        super(message);
    }
}
