package mx.quest.database.console;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import mx.quest.database.internal.ConnectionTester;
import mx.quest.database.internal.Consumer;
import mx.quest.database.internal.DatabaseCore;
import mx.quest.database.internal.DatabaseProps;
import mx.quest.database.internal.DatabaseRuntime;
import mx.quest.database.internal.DatabasesImpl;
import mx.quest.database.internal.Migrator;
import mx.quest.database.internal.Registry;
import mx.quest.database.spi.Scope;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The database screen in the application console ({@code /__app/database}): health, the two connection tests and
 * the migration recovery actions. Every handler needs {@code CONFIGURE_PLATFORM}, which the master and wildcard
 * administrators hold; a delegable plugin permission does not open it. Every state change is a CSRF-checked POST,
 * and every recovery action is logged with the operator's name.
 */
@Controller
@ConsoleScreen
@RequestMapping(DatabaseConsoleController.BASE)
public class DatabaseConsoleController {

    static final String BASE = "/__app/database";
    static final String GATE = "@authz.may('CONFIGURE_PLATFORM')";
    static final String CONTENT = "__plugin__/database/health";
    static final String TITLE_KEY = "database.console.title";
    static final String FLASH = "databaseFlash";
    static final String FLASH_ERROR = "databaseError";

    private static final Logger log = LoggerFactory.getLogger(DatabaseConsoleController.class);

    private final DatabaseScreens screens;
    private final ConnectionTester tester;
    private final DatabaseCore core;
    private final DatabasesImpl databases;
    private final Messages messages;

    public DatabaseConsoleController(DatabaseScreens screens, ConnectionTester tester, DatabaseCore core,
                                     DatabasesImpl databases, Messages messages) {
        this.screens = screens;
        this.tester = tester;
        this.core = core;
        this.databases = databases;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(GATE)
    public String health(Model model) {
        model.addAttribute("db", screens.health());
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, CONTENT);
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, TITLE_KEY);
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @PostMapping("/test-running")
    @PreAuthorize(GATE)
    public String testRunning(RedirectAttributes flash) {
        return result(flash, tester.running());
    }

    @PostMapping("/test-new")
    @PreAuthorize(GATE)
    public String testNew(@RequestParam(name = "provider", defaultValue = "") String provider,
                          @RequestParam(name = "namespace", defaultValue = "") String namespace,
                          @RequestParam(name = "host", defaultValue = "") String host,
                          @RequestParam(name = "port", defaultValue = "") String port,
                          @RequestParam(name = "database", defaultValue = "") String database,
                          @RequestParam(name = "username", defaultValue = "") String username,
                          @RequestParam(name = "password", defaultValue = "") String password,
                          @RequestParam(name = "tls", defaultValue = "") String tls,
                          @RequestParam(name = "caCertificate", defaultValue = "") String caCertificate,
                          RedirectAttributes flash) {
        DatabaseProps props = new DatabaseProps();
        props.setProvider(provider);
        props.setNamespace(namespace.isBlank() ? null : namespace);
        props.setHost(host);
        props.setDatabase(database);
        props.setUsername(username);
        props.setTls(tls);
        props.setCaCertificate(caCertificate);
        if (!port.isBlank()) {
            try {
                props.setPort(Integer.parseInt(port.strip()));
            } catch (NumberFormatException e) {
                return back(flash, FLASH_ERROR, console("database.test.invalid", "The settings cannot be used: {0}",
                        console("database.test.port", "the port is not a number")));
            }
        }
        return result(flash, tester.newSettings(props, password));
    }

    @PostMapping("/recover")
    @PreAuthorize(GATE)
    public String recover(@RequestParam(name = "schema") String schema,
                          @RequestParam(name = "plugin") String plugin,
                          @RequestParam(name = "action") String action,
                          RedirectAttributes flash) {
        Migrator.Recovery recovery;
        try {
            recovery = Migrator.Recovery.valueOf(action.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return back(flash, FLASH_ERROR, console("database.recover.unknown", "Unknown action."));
        }
        DatabaseRuntime runtime = core.runtime();
        if (!runtime.available()) {
            return back(flash, FLASH_ERROR, console("database.recover.unavailable",
                    "The database is not available."));
        }
        try {
            // Act only on a problem the registry recorded: the form's values are never trusted as names.
            Optional<Registry.Issue> issue = runtime.registry().issues().stream()
                    .filter(i -> i.schema().equals(schema) && i.pluginId().equals(plugin)).findFirst();
            Optional<Consumer> consumer = databases.consumers().stream().filter(c -> c.pluginId().equals(plugin))
                    .findFirst();
            if (issue.isEmpty() || consumer.isEmpty()) {
                return back(flash, FLASH_ERROR, console("database.recover.none",
                        "There is no such problem to act on (it may have been resolved)."));
            }
            Registry.Issue i = issue.get();
            Scope.Kind kind = i.schema().equals(runtime.registry().appSchema()) ? Scope.Kind.APP : Scope.Kind.SITE;
            core.migrator().recover(consumer.get(), kind, i.schema(), recovery);
            log.warn("database: operator '{}' ran {} for plugin '{}' in schema '{}' (was {} at version {})",
                    operator(), recovery, plugin, i.schema(), i.state(), i.version());
        } catch (SQLException | RuntimeException e) {
            log.warn("database: recovery {} for plugin '{}' in schema '{}' failed: {}", recovery, plugin, schema,
                    DatabaseRuntime.describe(e));
            return back(flash, FLASH_ERROR, console("database.recover.failed", "The action failed; see the log."));
        }
        return back(flash, FLASH, console("database.recover.done",
                "Done. The plugin checks its tables again on its next database call."));
    }

    private String result(RedirectAttributes flash, ConnectionTester.Result result) {
        String key = "database.test." + result.category().name().toLowerCase(Locale.ROOT);
        String text = switch (result.category()) {
            case OK -> console(key, "Connected: {0}.", result.detail());
            case INVALID_SETTINGS -> console(key, "The settings cannot be used: {0}", result.detail());
            case NO_PROVIDER -> console(key, "The provider plugin {0} is not installed.", result.detail());
            case UNREACHABLE -> console(key, "The server could not be reached.");
            case TLS -> console(key, "The TLS handshake failed (certificate or TLS settings).");
            case AUTHENTICATION -> console(key, "The server refused the username or password.");
            case DATABASE_MISSING -> console(key, "The database does not exist on the server, or the account has no "
                    + "access to it.");
            case UNSUPPORTED_SERVER -> console(key, "The server is not supported: {0}", result.detail());
            case NOT_AVAILABLE -> console(key, "The database is not in use: {0}", result.detail());
            default -> console("database.test.other", "The test failed; see the server log.");
        };
        return back(flash, result.category() == ConnectionTester.Category.OK ? FLASH : FLASH_ERROR, text);
    }

    private static String operator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }

    private String console(String code, String fallback, Object... args) {
        return messages.console(code, fallback, args);
    }

    private static String back(RedirectAttributes flash, String kind, String text) {
        flash.addFlashAttribute(kind, text);
        return "redirect:" + BASE;
    }
}
