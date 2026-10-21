package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The operator-request predicate that routes i18n ({@link OperatorAwareMessageSource}): the two realms' consoles,
 * their logins, and their pre-auth password-recovery pages resolve against the operator bundle; a genuine public
 * tenant path never does.
 */
class OperatorPathsTest {

    @Test
    void consoleAndLoginPathsAreOperatorRequests() {
        assertTrue(OperatorPaths.isOperatorRequest("/__app"));
        assertTrue(OperatorPaths.isOperatorRequest("/__app/sites"));
        assertTrue(OperatorPaths.isOperatorRequest("/__admin"));
        assertTrue(OperatorPaths.isOperatorRequest("/__admin/pages"));
        assertTrue(OperatorPaths.isOperatorRequest("/app/login"));
        assertTrue(OperatorPaths.isOperatorRequest("/login"));
    }

    @Test
    void recoveryPagesAreOperatorRequests() {
        // Regression: these rendered ??op.recovery.*?? when they were omitted here and fell to the site bundle.
        assertTrue(OperatorPaths.isOperatorRequest("/app/forgot-password"));
        assertTrue(OperatorPaths.isOperatorRequest("/app/reset"));
        assertTrue(OperatorPaths.isOperatorRequest("/forgot-password"));
        assertTrue(OperatorPaths.isOperatorRequest("/reset"));
    }

    @Test
    void publicAndNullPathsAreNotOperatorRequests() {
        assertFalse(OperatorPaths.isOperatorRequest("/"));
        assertFalse(OperatorPaths.isOperatorRequest("/about"));
        assertFalse(OperatorPaths.isOperatorRequest("/some/tenant/page"));
        // A public page that merely starts with a reserved prefix's letters is not an exact match.
        assertFalse(OperatorPaths.isOperatorRequest("/resets-and-more"));
        assertFalse(OperatorPaths.isOperatorRequest(null));
    }
}
