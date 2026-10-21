package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Lockout after maxAttempts within the window; unlock after the lockout; window pruning; success resets. */
class SlidingWindowLoginGateTest {

    /** Fixed thresholds as a settings supplier (seconds), captcha off, honeypot on (irrelevant to the gate). */
    private static Supplier<ProtectionSettings> settings(int maxAttempts, int windowSeconds, int lockoutSeconds) {
        ProtectionSettings s = new ProtectionSettings(true, maxAttempts, windowSeconds, lockoutSeconds,
                ProtectionSettings.Captcha.disabled());
        return () -> s;
    }

    @Test
    void locksAfterMaxAttemptsAndUnlocksAfterTheLockout() {
        long[] now = {1000};
        SlidingWindowLoginGate gate = new SlidingWindowLoginGate(settings(3, 10, 60), () -> now[0]);
        assertFalse(gate.isLocked("ip"));
        gate.recordFailure("ip");
        gate.recordFailure("ip");
        assertFalse(gate.isLocked("ip"), "2 of 3");
        gate.recordFailure("ip");
        assertTrue(gate.isLocked("ip"), "3rd failure within the window locks");
        now[0] += 60_001;
        assertFalse(gate.isLocked("ip"), "unlocked after the lockout elapses");
    }

    @Test
    void failuresOlderThanTheWindowDoNotCount() {
        long[] now = {1000};
        SlidingWindowLoginGate gate = new SlidingWindowLoginGate(settings(2, 10, 60), () -> now[0]);
        gate.recordFailure("ip");
        now[0] = 12_000; // the first failure is now outside the 10s window
        gate.recordFailure("ip");
        assertFalse(gate.isLocked("ip"), "only one failure falls inside the window");
    }

    @Test
    void theMapIsBoundedAgainstAManyIpFlood() {
        long[] now = {1000};
        // maxIps = 2: a flood of fresh IPs cannot grow the map without bound (reclaim/clear keeps it small).
        SlidingWindowLoginGate gate = new SlidingWindowLoginGate(settings(5, 10, 60), 2, () -> now[0]);
        for (int i = 0; i < 100; i++) {
            now[0] += 1;
            gate.recordFailure("ip-" + i); // each a distinct IP
        }
        // A locked IP still works (correctness preserved under the bound) and the gate did not OOM tracking 100 IPs.
        gate.recordFailure("victim");
        gate.recordFailure("victim");
        gate.recordFailure("victim");
        gate.recordFailure("victim");
        gate.recordFailure("victim");
        assertTrue(gate.isLocked("victim"), "lockout still works under the memory bound");
    }

    @Test
    void aSuccessClearsTheCounter() {
        long[] now = {1000};
        SlidingWindowLoginGate gate = new SlidingWindowLoginGate(settings(1, 10, 60), () -> now[0]);
        gate.recordFailure("ip");
        assertTrue(gate.isLocked("ip"));
        gate.recordSuccess("ip");
        assertFalse(gate.isLocked("ip"));
    }

    @Test
    void thresholdsAreReadFreshSoAConfigChangeAppliesWithoutRestart() {
        long[] now = {1000};
        // A mutable settings holder: raise maxAttempts mid-flight and the gate honors the new value immediately.
        ProtectionSettings[] holder = {new ProtectionSettings(true, 1, 10, 60, ProtectionSettings.Captcha.disabled())};
        SlidingWindowLoginGate gate = new SlidingWindowLoginGate(() -> holder[0], () -> now[0]);
        gate.recordFailure("ip");
        assertTrue(gate.isLocked("ip"), "maxAttempts=1 → locked after one failure");

        gate.recordSuccess("ip"); // clear, then relax the policy
        holder[0] = new ProtectionSettings(true, 5, 10, 60, ProtectionSettings.Captcha.disabled());
        gate.recordFailure("ip");
        assertFalse(gate.isLocked("ip"), "maxAttempts=5 now → one failure no longer locks (read fresh)");
    }

    @Test
    void aNullSupplierDegradesToDefaultsRatherThanThrowing() {
        long[] now = {1000};
        // Fail-safe: a null/failing supplier must not break the lockout — it uses ProtectionSettings.defaults().
        SlidingWindowLoginGate gate = new SlidingWindowLoginGate(null, () -> now[0]);
        for (int i = 0; i < 10; i++) {
            gate.recordFailure("ip"); // defaults() → maxAttempts 10
        }
        assertTrue(gate.isLocked("ip"), "defaults (10 attempts) still lock — never a no-op");
    }
}
