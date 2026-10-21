package mx.quest.runway.recovery;

import mx.quest.runway.spi.model.Realm;

/**
 * A single stored password-recovery grant — the server-side record behind an emailed reset link. The link
 * itself carries a random <b>plaintext</b> token; only its {@code SHA-256} hash is stored here, so the store
 * file never contains a usable token (a stolen store cannot reset anyone). Each grant is single-use (removed on
 * a successful reset) and time-limited ({@link #expiresEpochMs}).
 *
 * @param tokenHash      the hex SHA-256 of the plaintext token in the link — the lookup key
 * @param realm          the realm the grant belongs to ({@link Realm#APPLICATION} or {@link Realm#TENANT}); a
 *                       grant minted for one realm can never reset an account in the other
 * @param siteId         for a {@link Realm#TENANT} grant, the site the account belongs to (re-checked against
 *                       the resolved host on reset, so a grant for site A cannot be used on site B); {@code null}
 *                       for an application grant
 * @param principalId    the stable id of the account this grant resets
 * @param username       the account's login name, for the reset page and audit logging (not sensitive — the
 *                       grant holder proved control of the account's recovery email)
 * @param expiresEpochMs the wall-clock instant (epoch millis) after which the grant is no longer valid
 */
public record RecoveryToken(String tokenHash, Realm realm, String siteId, String principalId, String username,
                            long expiresEpochMs) {

    /** Whether this grant is still within its validity window relative to {@code nowEpochMs}. */
    public boolean isValidAt(long nowEpochMs) {
        return nowEpochMs < expiresEpochMs;
    }
}
