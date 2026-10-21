package mx.quest.runway.protect;

/**
 * One rejection a guard declares it may produce — a {@code status}/{@code reason} <b>pair</b>, which is
 * exactly the label set of the {@code runway.edge.rejections} counter.
 *
 * <h2>Why a pair and not a set of reasons</h2>
 * Registering the cross product of "statuses core knows" × "reasons a guard uses" would emit series that
 * assert something untrue — a {@code 429} tagged {@code blocklist-manual} says an operator ban is
 * back-pressure, which no code path can produce. A zero series that cannot happen is worse than an absent
 * one: absence is merely uninformative, while a false zero is read as evidence. Declaring pairs keeps the
 * registered set equal to the reachable set.
 *
 * <h2>Why the guard declares them and the kernel does not</h2>
 * Only {@link EdgeVerdict#UNSPECIFIED} is a reason the kernel defines. Every meaningful one
 * ({@code rate-limit}, {@code blocklist-auto}, {@code blocklist-manual}, {@code blocklist-cidr}) is a string
 * supplied by the protection plugin, and hard-coding a plugin's vocabulary in core is precisely what the
 * plugin architecture exists to prevent. So the declaration travels with the code that produces it.
 *
 * @param status the HTTP status this rejection answers with
 * @param reason the short reason tag it carries (see {@link EdgeVerdict#reason()})
 */
public record RejectionOutcome(int status, String reason) {

    /** @throws IllegalArgumentException if {@code reason} is null or blank — an untagged series is not useful */
    public RejectionOutcome {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a declared rejection outcome needs a reason tag");
        }
    }
}
