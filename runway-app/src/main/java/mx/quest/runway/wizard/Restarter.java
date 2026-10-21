package mx.quest.runway.wizard;

/**
 * Triggers the WIZARD→ACTIVE transition. Per the locked lifecycle decision, Runway does <b>no in-JVM
 * restart</b>: the running instance shuts down gracefully and a supervisor relaunches it (systemd
 * {@code RestartForceExitStatus=42}, the launcher's relaunch loop, or a manual restart), so the fresh
 * process re-reads the external {@code application.yml} the wizard just wrote and comes up ACTIVE.
 */
public interface Restarter {

    /**
     * The exit code the supervisor treats as "relaunch me" (systemd {@code RestartForceExitStatus=42}, the
     * launcher's relaunch loop, or a manual restart). Shared by every {@link Restarter} so the contract with
     * the supervisor is stated in exactly one place: both the WIZARD→ACTIVE transition and the ACTIVE-mode
     * plugin-install restart leave the JVM with this code, and the launcher/unit relaunches identically.
     */
    int RESTART_EXIT_CODE = 42;

    /** Gracefully shuts down so the supervisor relaunches the process. */
    void restart();
}
