package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The CSP report collector: it never throws (always the annotated {@code 204}), parses BOTH the classic
 * {@code {"csp-report": …}} object and the Reporting-API array, logs the violation directive/URIs at WARN, and
 * truncates an unparseable body to {@link CspReportController#RAW_BODY_CAP} rather than echoing it whole.
 */
class CspReportControllerTest {

    private final CspReportController controller = new CspReportController();

    private Logger controllerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        controllerLogger = (Logger) LoggerFactory.getLogger(CspReportController.class);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        controllerLogger.detachAppender(appender);
    }

    private String warnMessages() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            all.append(event.getFormattedMessage()).append('\n');
        }
        return all.toString();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** The 204 is declarative: the handler is annotated @ResponseStatus(NO_CONTENT) and the method returns void. */
    @Test
    void handlerDeclaresNoContentAndReturnsVoid() throws Exception {
        Method report = CspReportController.class.getMethod("report", byte[].class);
        assertThat(report.getReturnType()).isEqualTo(void.class);
        ResponseStatus status = report.getAnnotation(ResponseStatus.class);
        assertThat(status).isNotNull();
        assertThat(status.value()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void classicReportShapeIsParsedAndLogged() {
        String body = "{\"csp-report\":{\"document-uri\":\"https://acme.test/page\","
                + "\"violated-directive\":\"script-src\",\"effective-directive\":\"script-src\","
                + "\"blocked-uri\":\"https://evil.example/x.js\"}}";
        assertThatCode(() -> controller.report(bytes(body))).doesNotThrowAnyException();
        String logged = warnMessages();
        assertThat(logged).contains("CSP violation");
        assertThat(logged).contains("directive='script-src'");
        assertThat(logged).contains("blocked='https://evil.example/x.js'");
        assertThat(logged).contains("doc='https://acme.test/page'");
        assertThat(logged).doesNotContain("unparsed");
    }

    @Test
    void reportingApiArrayShapeIsParsedAndLogged() {
        String body = "[{\"type\":\"csp-violation\",\"body\":{\"documentURL\":\"https://acme.test/home\","
                + "\"effectiveDirective\":\"img-src\",\"blockedURL\":\"https://tracker.example/pixel.gif\"}}]";
        assertThatCode(() -> controller.report(bytes(body))).doesNotThrowAnyException();
        String logged = warnMessages();
        assertThat(logged).contains("directive='img-src'");
        assertThat(logged).contains("blocked='https://tracker.example/pixel.gif'");
        assertThat(logged).contains("doc='https://acme.test/home'");
        assertThat(logged).doesNotContain("unparsed");
    }

    @Test
    void reportingApiArrayWithMultipleEntriesLogsEach() {
        String body = "[{\"type\":\"csp-violation\",\"body\":{\"effectiveDirective\":\"script-src\"}},"
                + "{\"type\":\"csp-violation\",\"body\":{\"effectiveDirective\":\"style-src\"}}]";
        controller.report(bytes(body));
        assertThat(appender.list).hasSize(2);
        assertThat(warnMessages()).contains("directive='script-src'").contains("directive='style-src'");
    }

    @Test
    void emptyOrNullBodyIsIgnoredSilently() {
        assertThatCode(() -> controller.report(null)).doesNotThrowAnyException();
        assertThatCode(() -> controller.report(new byte[0])).doesNotThrowAnyException();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void unparseableBodyIsCappedNotEchoedWhole() {
        String junk = "x".repeat(5000); // far larger than the cap, and not JSON
        assertThatCode(() -> controller.report(bytes(junk))).doesNotThrowAnyException();
        String logged = warnMessages();
        assertThat(logged).contains("unparsed");
        // The raw echo is capped: the full 5000-char body must not appear, and the ellipsis marks truncation.
        assertThat(logged).doesNotContain(junk);
        assertThat(logged).contains("…");
        assertThat(logged).contains("x".repeat(CspReportController.RAW_BODY_CAP));
        assertThat(logged).doesNotContain("x".repeat(CspReportController.RAW_BODY_CAP + 1));
    }
}
