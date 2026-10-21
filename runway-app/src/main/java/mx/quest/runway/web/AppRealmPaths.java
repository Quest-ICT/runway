package mx.quest.runway.web;

import java.util.List;
import java.util.stream.Stream;

/**
 * The <b>one</b> definition of the application realm's paths. {@code AppSecurityConfig} builds its chain's matcher
 * from {@link #securityMatchers()}, and every filter that asks "is this the application realm" uses
 * {@link #isAppRealm} — so the chain and the filters cannot drift apart. They did once: the recovery routes
 * ({@code /app/forgot-password}, {@code /app/reset}) were added to the chain but not here, so a suspended or
 * maintenance default site, or a broken essential plugin, answered {@code 503} on the operator's password recovery
 * while the sign-in page next to it worked.
 *
 * <p>The realm: {@code /__app} and everything under it, the console's sign-in, sign-out and password recovery
 * ({@code /app/login}, {@code /app/logout}, {@code /app/forgot-password}, {@code /app/reset}), and
 * {@code /actuator} and everything under it.
 *
 * <p>Exact matches, never a loose {@code startsWith}: {@code /__apple}, {@code /actuatorX} and {@code /app/login2}
 * are not the realm (a tenant-band filter that exempted them would serve them past the {@code 503}).
 *
 * <p>These predicates take a <b>decoded</b> path. Pass {@link RequestPaths#normalized} (or, for a protective
 * check, {@link RequestPaths#eitherMatches}), never the raw {@code getRequestURI()}: {@code /%5F%5Fapp} is the
 * console to Spring, and not the console to a raw-string check.
 */
public final class AppRealmPaths {

    /** The operator console's namespace: the path itself and everything under it. */
    public static final String CONSOLE_PREFIX = "/__app";

    /** Spring Boot Actuator's namespace (monitoring; reached by address, no browser session). */
    public static final String ACTUATOR_PREFIX = "/actuator";

    /** The console's single-path routes outside {@link #CONSOLE_PREFIX}: sign-in, sign-out, password recovery. */
    public static final List<String> CONSOLE_ROUTES =
            List.of("/app/login", "/app/logout", "/app/forgot-password", "/app/reset");

    private AppRealmPaths() {
    }

    /**
     * The application security chain's matcher patterns ({@code AppSecurityConfig#securityMatcher}), built from the
     * same constants as {@link #isAppRealm}: {@code /__app/**}, the four console routes, {@code /actuator/**}.
     * ({@code /x/**} also matches {@code /x} itself, as {@link #isAppRealm} does.)
     */
    public static String[] securityMatchers() {
        return Stream.concat(
                        Stream.of(CONSOLE_PREFIX + "/**"),
                        Stream.concat(CONSOLE_ROUTES.stream(), Stream.of(ACTUATOR_PREFIX + "/**")))
                .toArray(String[]::new);
    }

    /** Whether the decoded {@code path} is an application-realm path: the console or {@code /actuator}. */
    public static boolean isAppRealm(String path) {
        return isOperatorConsole(path) || inNamespace(path, ACTUATOR_PREFIX);
    }

    /**
     * Whether the decoded {@code path} belongs to the <b>operator console</b>: the whole realm except
     * {@code /actuator/**}, which monitoring reaches by address with a token. An operator's session cookie, if a
     * browser sent it there, would also authorize it on any host; browsers scope that cookie to the host it was set
     * on, so a tenant page cannot make the browser send it. This is the
     * set the console-host restriction ({@link ConsoleHostFilter}) guards.
     */
    public static boolean isOperatorConsole(String path) {
        return path != null && (inNamespace(path, CONSOLE_PREFIX) || CONSOLE_ROUTES.contains(path));
    }

    /** {@code prefix} itself or a path under it — never a sibling that merely starts with the same characters. */
    public static boolean inNamespace(String path, String prefix) {
        return path != null && (path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
