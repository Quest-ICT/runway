package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Views must BUFFER, not stream — {@code spring.thymeleaf.servlet.produce-partial-output-while-processing=false}.
 *
 * <p>With streaming (Spring Boot's default) an exception raised <b>mid-render</b> — e.g. a plugin fragment with a
 * bad expression — flushes partial output and <b>commits</b> the response before the container can dispatch to
 * {@code /error}, so the client gets a blank/truncated page and <b>no</b> error page (a silent failure — the exact
 * symptom the contact/protection fragment bugs produced). Buffering means the fault surfaces <b>before any byte is
 * written</b>, so {@link RunwayErrorController}'s self-contained error page is shown every time. This test guards the
 * setting against removal; {@code RunwayErrorControllerTest} covers that the page itself renders correctly.
 */
@SpringBootTest
class ThymeleafBufferingTest {

    @Autowired
    private ThymeleafViewResolver thymeleafViewResolver;

    @Test
    void viewsBufferSoAMidRenderErrorSurfacesTheErrorPageNotABlank() {
        assertFalse(thymeleafViewResolver.getProducePartialOutputWhileProcessing(),
                "views must buffer (produce-partial-output-while-processing=false) so a render fault is caught before "
                        + "the response commits and RunwayErrorController's error page is shown, never a silent blank");
    }
}
