package mx.quest.runway.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the operator console answers, bound from {@code runway.console.*}. Read by {@link ConsoleHostFilter}.
 *
 * <p>Host-only on purpose: a wrong value locks every operator out of the console, so the console must not be able
 * to set it, and the file on the server ({@code external/application.yml}) is the way back in. Read once at
 * startup; a change needs a restart, and each node of a cluster reads its own file.
 */
@ConfigurationProperties("runway.console")
public class ConsoleHostProperties {

    /**
     * The host names the operator console (/__app, /app/login, /app/logout, /app/forgot-password, /app/reset) answers
     * on, compared without case and without the port. Every other host gets a plain 404 for those paths. Empty (the
     * default): the console answers on the default site's host names and on host names that serve no site (the
     * server's IP address, localhost), but not on a host name that serves any other site.
     */
    private List<String> hosts = new ArrayList<>();

    public List<String> getHosts() {
        return hosts;
    }

    public void setHosts(List<String> hosts) {
        this.hosts = hosts == null ? new ArrayList<>() : hosts;
    }
}
