package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.cache.TemplateCacheKey;
import org.thymeleaf.context.Context;
import org.thymeleaf.engine.TemplateModel;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * A plugin template is an operator-editable file in the plugin's install dir, and a virtual render's ETag folds its
 * bytes. Its cached parse must therefore follow the file: an edit on disk renders on the next request (the former
 * 30-second TTL served the old parse under the new ETag for up to 30 s), an untouched file keeps its cached parse.
 */
class PluginTemplateOnDiskEditTest {

    private static final String NAME = PluginTemplateResolver.PREFIX + "blog/card";

    private static SpringTemplateEngine engine(Path pluginDir) {
        PluginDescriptor descriptor = new PluginDescriptor("blog", "Blog", "1.0.0", null, null, null,
                null, null, null, null, null, null, null, null, null);
        LoadedPlugin plugin = new LoadedPlugin(descriptor, pluginDir);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new PluginTemplateResolver(() -> List.of(plugin)));
        return engine;
    }

    private static Path writeCard(Path pluginDir, String text) throws IOException {
        Path file = pluginDir.resolve("templates").resolve("card.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<!DOCTYPE html><html><body><p>" + text + "</p></body></html>");
        return file;
    }

    private static String render(SpringTemplateEngine engine) {
        return engine.process(NAME, new Context(Locale.ENGLISH));
    }

    private static TemplateModel cached(SpringTemplateEngine engine) {
        var cache = engine.getCacheManager().getTemplateCache();
        for (TemplateCacheKey key : cache.keySet()) {
            if (NAME.equals(key.getTemplate())) {
                return cache.get(key);
            }
        }
        return null;
    }

    @Test
    void anEditedPluginTemplateRendersOnTheNextRequest(@TempDir Path tmp) throws IOException {
        Path card = writeCard(tmp, "CARD-1");
        Files.setLastModifiedTime(card, FileTime.from(Instant.now().minusSeconds(3600)));
        SpringTemplateEngine engine = engine(tmp);
        assertTrue(render(engine).contains("CARD-1"));
        assertTrue(cached(engine) != null, "precondition: the parse is cached (cache ON)");

        writeCard(tmp, "CARD-2");

        assertTrue(render(engine).contains("CARD-2"), "no 30-second window: the edit shows at once");
    }

    @Test
    void anUntouchedPluginTemplateKeepsItsCachedParse(@TempDir Path tmp) throws IOException {
        Path card = writeCard(tmp, "CARD-1");
        Files.setLastModifiedTime(card, FileTime.from(Instant.now().minusSeconds(3600)));
        SpringTemplateEngine engine = engine(tmp);
        render(engine);
        TemplateModel first = cached(engine);

        render(engine);

        assertSame(first, cached(engine), "the cache is not defeated for an unchanged file");
    }
}
