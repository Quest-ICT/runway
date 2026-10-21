package mx.quest.runway.event;

/**
 * Marks the current thread as replaying an event a <b>peer</b> node published, so the event's listeners can tell a
 * local change from a relayed one. The cluster relay sets it around each replay; the relay itself reads it to avoid
 * re-broadcasting a replay, and the SPI bridge turns it into the {@code local} flag of every event a plugin receives
 * (a listener with an external side effect — an IndexNow submission — acts only on local events, so a cluster of N
 * nodes does not repeat it N times).
 *
 * <p>Per thread; always cleared by {@link #run}, also on failure.
 */
public final class ClusterReplay {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private ClusterReplay() {
    }

    /** Whether the current thread is replaying a peer's event. */
    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /**
     * Runs {@code replay} marked as a peer's event (nested calls keep the mark).
     *
     * @param replay the local re-publication of the received event
     */
    public static void run(Runnable replay) {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            replay.run();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }
}
