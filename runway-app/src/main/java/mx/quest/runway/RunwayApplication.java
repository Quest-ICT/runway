package mx.quest.runway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Runway kernel.
 *
 * <p>Plugin packages live OUTSIDE
 * this base package ({@code mx.quest.runway.plugins.*}) so the component scan never double-scans
 * them; they enter the context through their registered {@code @Configuration}.
 */
@SpringBootApplication
public class RunwayApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(RunwayApplication.class, args);
        } catch (Throwable failure) {
            // A startup failure that the wiring quarantine pinned on one plugin ends with the supervisor's relaunch
            // code: the marker it wrote makes the next boot load without that plugin. Anything else stays an
            // ordinary failure (Spring Boot has already reported it).
            java.util.OptionalInt relaunch = mx.quest.runway.plugin.PluginQuarantine.exitCodeAfterFailure();
            if (relaunch.isPresent()) {
                System.exit(relaunch.getAsInt());
            }
            throw failure;
        }
    }
}
