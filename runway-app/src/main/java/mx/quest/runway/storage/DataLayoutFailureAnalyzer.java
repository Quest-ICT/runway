package mx.quest.runway.storage;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Renders a {@link DataLayoutException} as Spring Boot's plain "APPLICATION FAILED TO START" report — a
 * description and an action — instead of a bare stack trace, because the reader is an operator who has just
 * started the wrong release against a data folder, not a developer. Registered in {@code META-INF/spring.factories}.
 */
public class DataLayoutFailureAnalyzer extends AbstractFailureAnalyzer<DataLayoutException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DataLayoutException cause) {
        String action = cause.isNewer()
                ? "Start the Runway release that wrote this folder (or a later one). If you must go back to this "
                        + "release, restore the folder from a copy taken while it was running this release. Do not "
                        + "edit or delete " + cause.marker() + " to get past this check: the files it describes "
                        + "may not be readable by this release."
                : "Restore " + cause.marker() + " from a copy of the data folder, or, if this folder has only ever "
                        + "been used by Runway 1.x, replace the file's contents with the single line 'version: 1'.";
        return new FailureAnalysis(cause.getMessage(), action, cause);
    }
}
