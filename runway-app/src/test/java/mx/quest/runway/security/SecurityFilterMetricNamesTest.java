package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mx.quest.runway.metrics.PrometheusScrapeAuthFilter;
import org.junit.jupiter.api.Test;

/**
 * Pins the class names of filters added to a Spring Security chain, because <b>those names are a public
 * production metric surface</b>.
 *
 * <h2>The surface nobody chose</h2>
 * Spring Security registers one meter per filter in a chain, <b>named after the filter class</b>:
 *
 * <pre>
 *   spring_security_filterchains_LoginProtectionFilter_before_total
 *   spring_security_filterchains_LoginProtectionFilter_after_total
 *   spring_security_filterchains_PrometheusScrapeAuthFilter_before_total
 *   spring_security_filterchains_PrometheusScrapeAuthFilter_after_total
 * </pre>
 *
 * <p>So renaming one of these classes — an ordinary, obviously-safe internal refactor — <b>silently renames
 * production metrics</b>. Dashboards lose their series, alert rules stop matching, and a monitoring
 * disappearance detector fires on what looks like an outage. Nothing catches it: not the compiler, not any
 * behavioural test, and not code review, because the rename looks entirely local.
 *
 * <p>Reported from a live deployment whose monitoring stack had to
 * exclude the whole {@code spring_security_filterchains_*} family to stop it firing.
 *
 * <h2>Why this test is written with string literals</h2>
 * The expected values are <b>deliberately</b> literals rather than {@code SomeFilter.class.getSimpleName()}
 * compared against itself. An IDE rename refactor updates every {@code .class} reference automatically, so a
 * self-referential assertion would rename right along with the class and guard nothing. A literal does not
 * move — the rename breaks this test, the developer reads the message, and the metric rename becomes a
 * <em>decision</em> instead of an accident.
 *
 * <p><b>If you are here because this test failed:</b> the rename is fine, but it is a breaking monitoring
 * change. Update the literal, and tell whoever owns the dashboards and alert rules before you ship it.
 */
class SecurityFilterMetricNamesTest {

    private static final String WHY =
            "This class name is part of a production metric name (spring_security_filterchains_<Class>_*). "
            + "Renaming it renames those metrics and breaks dashboards and alert rules. If the rename is "
            + "intended, update this literal AND notify whoever owns the monitoring stack.";

    @Test
    void theLoginProtectionFilterKeepsItsMetricName() {
        assertEquals("LoginProtectionFilter", LoginProtectionFilter.class.getSimpleName(), WHY);
    }

    @Test
    void thePrometheusScrapeAuthFilterKeepsItsMetricName() {
        assertEquals("PrometheusScrapeAuthFilter", PrometheusScrapeAuthFilter.class.getSimpleName(), WHY);
    }
}
