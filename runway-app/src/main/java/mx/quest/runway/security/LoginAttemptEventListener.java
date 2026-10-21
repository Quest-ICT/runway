package mx.quest.runway.security;

import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Feeds the {@link LoginAttemptGate} from Spring Security's authentication events, so the gate sees every login
 * outcome across both realm chains without each chain wiring its own handlers. The client IP is read from the
 * authentication's {@link WebAuthenticationDetails} — which the authentication filter sets from
 * {@code request.getRemoteAddr()}, i.e. the gated IP (the trusted-proxy filter has already run). With the
 * kernel's permissive default gate these records are no-ops; the essential protection plugin's gate
 * turns them into a real per-IP counter.
 */
public class LoginAttemptEventListener {

    private final LoginAttemptGate gate;

    public LoginAttemptEventListener(LoginAttemptGate gate) {
        this.gate = gate;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        clientIp(event.getAuthentication()).ifPresent(gate::recordSuccess);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        clientIp(event.getAuthentication()).ifPresent(gate::recordFailure);
    }

    private static Optional<String> clientIp(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof WebAuthenticationDetails details
                && details.getRemoteAddress() != null) {
            return Optional.of(details.getRemoteAddress());
        }
        return Optional.empty();
    }
}
