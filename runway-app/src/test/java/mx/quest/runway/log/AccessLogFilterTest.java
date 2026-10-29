package mx.quest.runway.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import mx.quest.runway.site.SiteContextFilter;

/**
 * The access log fires from the post-phase (final status) for public traffic, and skips internal surfaces. Which
 * requests reach it on the running application, whoever answers them, is {@code AccessLogCoverageEndToEndTest}.
 */
class AccessLogFilterTest {

    private Logger accessLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        accessLogger = (Logger) LoggerFactory.getLogger("runway.access");
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        accessLogger.detachAppender(appender);
    }

    private final SiteLogService siteLog = mock(SiteLogService.class);

    private AccessLogFilter filter() {
        return new AccessLogFilter(siteLog, false);
    }

    private void runWithFinalStatus(String path, int finalStatus) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setRemoteAddr("198.51.100.4");
        filter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(finalStatus));
    }

    @Test
    void internalSurfacesAreClassified() {
        assertTrue(AccessLogFilter.isInternal("/__app/x"));
        assertTrue(AccessLogFilter.isInternal("/__admin"));
        assertTrue(AccessLogFilter.isInternal("/app/login"));
        assertTrue(AccessLogFilter.isInternal("/actuator/health"));
        assertFalse(AccessLogFilter.isInternal("/about"));
        assertFalse(AccessLogFilter.isInternal("/"));
    }

    @Test
    void aPublicRequestLogsItsFinalStatus() throws Exception {
        runWithFinalStatus("/about", 418); // status set by the inner handler, observed in the post-phase
        assertEquals(1, appender.list.size());
        String line = appender.list.get(0).getFormattedMessage();
        assertTrue(line.contains("418"), "final status not logged: " + line);
        assertTrue(line.contains("/about"), line);
        assertTrue(line.contains("198.51.100.4"), "gated client IP not logged: " + line);
    }

    @Test
    void anInternalRequestIsNotLogged() throws Exception {
        runWithFinalStatus("/__app/dashboard", 200);
        assertTrue(appender.list.isEmpty(), "internal surfaces must not appear in the public access log");
    }

    @Test
    void itStillLogsWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        request.setRequestURI("/about");
        request.setRemoteAddr("198.51.100.4");
        RuntimeException boom = new RuntimeException("handler blew up");
        // The chain sets a status then throws — the finally block must still log (it's an access log).
        assertThrows(RuntimeException.class, () -> filter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    ((HttpServletResponse) res).setStatus(500);
                    throw boom;
                }));
        assertEquals(1, appender.list.size(), "the access log fires from finally even on an exception");
        assertTrue(appender.list.get(0).getFormattedMessage().contains("/about"));
    }

    @Test
    void theSiteThatAnsweredGetsTheLineFromTheRequestAttribute() throws Exception {
        // The site-context filter is inner of this one: by the post-phase its thread binding is gone, and only the
        // attribute it set on the request says which site answered.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRequestURI("/login");
        request.setRemoteAddr("198.51.100.4");
        filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            req.setAttribute(SiteContextFilter.SITE_ID_ATTRIBUTE, "acme");
            ((HttpServletResponse) res).setStatus(403);
        });
        verify(siteLog).access("acme", "198.51.100.4", "POST", "/login", 403);
    }

    @Test
    void aRequestNoSiteAnsweredGoesToThePlatformLogOnly() throws Exception {
        runWithFinalStatus("/about", 404);
        assertEquals(1, appender.list.size());
        verify(siteLog, never()).access(anyString(), anyString(), anyString(), anyString(), anyInt());
    }
}
