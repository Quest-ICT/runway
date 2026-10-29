package mx.quest.database.internal;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/** Small shared checks: failure causes and simple SQL names. */
final class Failures {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private Failures() {
    }

    /** Whether a TLS failure is anywhere in the cause chain. */
    static boolean hasTlsCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                return true;
            }
        }
        return false;
    }

    /** Whether a network failure (refused, unknown host, timeout, no route) is anywhere in the cause chain. */
    static boolean hasNetworkCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof SocketTimeoutException || t instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /**
     * A lower-case, unquoted SQL name (the convention for consumer objects), checked before it goes into a
     * statement's text.
     */
    static String requireSimpleName(String name) {
        if (name == null || !SIMPLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a lower-case SQL name: " + name);
        }
        return name;
    }
}
