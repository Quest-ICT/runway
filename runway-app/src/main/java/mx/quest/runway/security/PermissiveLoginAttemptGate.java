package mx.quest.runway.security;

/**
 * The kernel's default {@link LoginAttemptGate}: it never locks and ignores the records. Named (not an anonymous
 * class) so the kernel can <b>positively detect</b> that an essential protection plugin's {@code @Primary}
 * override took effect — if an allowlisted-essential plugin loaded but the resolved {@code LoginAttemptGate} is
 * still this class, the override silently failed and the platform must fail closed.
 */
public final class PermissiveLoginAttemptGate implements LoginAttemptGate {

    @Override
    public boolean isLocked(String clientIp) {
        return false;
    }

    @Override
    public void recordFailure(String clientIp) {
        // no-op until a policy gate is supplied
    }

    @Override
    public void recordSuccess(String clientIp) {
        // no-op until a policy gate is supplied
    }
}
