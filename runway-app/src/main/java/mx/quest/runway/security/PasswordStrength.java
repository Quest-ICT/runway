package mx.quest.runway.security;

/**
 * The Argon2id memory-cost profile. Both profiles share salt 16 / hash 32 / parallelism 1 /
 * iterations 2 (the OWASP Argon2id baseline); only the memory cost differs. {@link #STANDARD} is the
 * default; {@link #HIGH} is for higher-value credential stores. Because Argon2id PHC strings are
 * self-describing (the cost parameters travel with each hash), changing the active profile only
 * affects newly minted hashes and never breaks verification of existing ones.
 */
public enum PasswordStrength {

    /** OWASP minimum for Argon2id — 19 MiB. */
    STANDARD(19 * 1024),

    /** Higher cost for sensitive stores — 32 MiB. */
    HIGH(32 * 1024);

    private final int memoryKiB;

    PasswordStrength(int memoryKiB) {
        this.memoryKiB = memoryKiB;
    }

    /** The Argon2id memory cost, in KiB. */
    public int memoryKiB() {
        return memoryKiB;
    }
}
