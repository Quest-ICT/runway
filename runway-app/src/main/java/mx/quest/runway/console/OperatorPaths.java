package mx.quest.runway.console;

import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.TenantConsolePaths;

/**
 * The single predicate for "is this request an <b>operator-console</b> request" — the two operator realms'
 * surfaces ({@code /__app/**} + {@code /app/login|logout}, and {@code /__admin/**} + {@code /login|logout}), their
 * login endpoints, and their pre-auth <b>password-recovery</b> pages ({@code /app/forgot-password|reset} and the
 * reserved bare {@code /forgot-password|reset}). It decides i18n routing ({@link OperatorAwareMessageSource}):
 * every operator-rendered page resolves {@code #{}} against the operator bundle, <b>never</b> a bound tenant's
 * site strings — even on {@code /__admin}, where a tenant <em>is</em> the subject but the chrome is still platform
 * copy. Omitting the recovery pages here made them resolve against the site bundle, rendering their labels as
 * {@code ??op.recovery.*??}.
 *
 * <p>Exact matches (not loose {@code startsWith}) for the bare, login and recovery paths, matching the realm
 * chains, so a public tenant path can never be mistaken for operator chrome. The recovery paths are reserved
 * platform routes (like {@code /login}); a tenant page can no more sit at {@code /forgot-password} than at
 * {@code /login}.
 */
public final class OperatorPaths {

    private OperatorPaths() {
    }

    /**
     * Whether the decoded, context-stripped {@code path} ({@link mx.quest.runway.web.RequestPaths#normalized}) is
     * served by the operator console: the application console ({@link AppRealmPaths#isOperatorConsole}) or a page of
     * a tenant console ({@link TenantConsolePaths#isConsolePage}). Built from those two, so the sets cannot drift.
     */
    public static boolean isOperatorRequest(String path) {
        return AppRealmPaths.isOperatorConsole(path) || TenantConsolePaths.isConsolePage(path);
    }
}
