package mx.quest.runway.security;

import java.util.Objects;

/**
 * The Argon2id profile this process hashes new passwords with, as chosen once at startup (the Platform settings
 * override when present and valid, else {@code runway.security.password-strength}).
 *
 * <p>A bean of its own so that what the console reports is what the encoder does: the Platform settings value can
 * change after startup, and it takes effect only on the next launch.
 *
 * @param strength the profile in force; never {@code null}
 */
public record ActivePasswordStrength(PasswordStrength strength) {

    public ActivePasswordStrength {
        Objects.requireNonNull(strength, "strength");
    }
}
