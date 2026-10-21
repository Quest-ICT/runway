package mx.quest.blog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.core.convert.support.DefaultConversionService;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;

/**
 * Renders this plugin's SHIPPED templates ({@code templates/*.html} + their decoupled {@code .th.xml} logic) with
 * Thymeleaf's own Spring engine — no kernel class. The templates are copied under the logical names the kernel uses
 * ({@code __plugin__/blog/<name>}), a site master can be added next to them, and {@code #{…}} resolves from this
 * plugin's own bundles ({@code i18n/messages*.properties}), as the kernel appends them.
 *
 * <p>What this cannot see is anything the kernel adds beyond the documented render model — which is the point: a
 * template that renders here needs nothing but the contract.
 */
final class TemplateHarness {

    private final Path root;
    private final SpringTemplateEngine engine;

    TemplateHarness(Path root) {
        this.root = root;
        try {
            Path target = root.resolve("__plugin__/blog");
            Files.createDirectories(target);
            try (Stream<Path> files = Files.list(Path.of("templates"))) {
                for (Path file : files.toList()) {
                    Files.copy(file, target.resolve(file.getFileName().toString()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(root.toAbsolutePath() + "/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setUseDecoupledLogic(true);
        resolver.setCacheable(false);
        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasename("file:" + Path.of("i18n/messages").toAbsolutePath());
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
    }

    /** Writes a site master ({@code <name>.html} + an empty logic file) and returns its logical name. */
    String master(String name, String html) {
        try {
            Path file = root.resolve(name + ".html");
            Files.createDirectories(file.getParent());
            Files.writeString(file, html, StandardCharsets.UTF_8);
            Files.writeString(root.resolve(name + ".th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>",
                    StandardCharsets.UTF_8);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String render(String template, Locale locale, Map<String, Object> vars) {
        GenericApplicationContext app = new GenericApplicationContext();
        app.refresh();
        Context ctx = new Context(locale);
        vars.forEach(ctx::setVariable);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(app, DefaultConversionService.getSharedInstance()));
        return engine.process(template, ctx);
    }
}
