package mx.quest.runway.crypto;

/**
 * Symmetric encryption of stored secrets (AES-256-GCM) in the tagged {@code ENC(<base64>)} form — the kernel
 * seam plugins inject to decrypt their {@code secret}-typed settings (which bind as the raw {@code ENC(...)}
 * string via {@code @ConfigurationProperties}), and the settings service uses to encrypt them on save.
 *
 * <p>Encryption is <b>non-deterministic</b> (a fresh GCM nonce each call), so the same plaintext yields a
 * different ciphertext every time — never compare secrets by their stored form. The cipher is unavailable
 * until the installation's master key ({@code runway.security.secret-key}) is configured; callers must check
 * {@link #isAvailable()} (or be ready for {@link IllegalStateException}) before encrypting.
 */
public interface SecretCipher {

    /** Whether a master key is configured, so {@link #encrypt(String)} can run. */
    boolean isAvailable();

    /**
     * Encrypts {@code plaintext} and returns the tagged {@code ENC(...)} form for storage.
     *
     * @throws IllegalStateException if no master key is configured ({@link #isAvailable()} is false)
     */
    String encrypt(String plaintext);

    /**
     * Returns the plaintext of a value: decrypts a tagged {@code ENC(...)} value, or returns a non-tagged
     * value unchanged (so a hand-written plaintext secret still works). Throws only on a tagged value that
     * fails to decrypt (wrong key / corrupt ciphertext).
     */
    String decrypt(String value);

    /** Whether {@code value} is in the tagged {@code ENC(...)} form (so it is an encrypted secret). */
    boolean isEncrypted(String value);
}
