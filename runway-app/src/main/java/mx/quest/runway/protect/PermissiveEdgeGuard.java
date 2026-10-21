package mx.quest.runway.protect;

/**
 * The kernel's default {@link EdgeGuard}: it passes everything. Named (not a lambda) so the kernel can
 * <b>positively detect</b> that an essential protection plugin's {@code @Primary} override actually took effect
 * — if an allowlisted-essential plugin loaded but the resolved {@code EdgeGuard} is still this class, the
 * override silently failed and the platform must fail closed rather than serve open.
 */
public final class PermissiveEdgeGuard implements EdgeGuard {

    @Override
    public EdgeVerdict check(EdgeRequest request) {
        return EdgeVerdict.pass();
    }
}
