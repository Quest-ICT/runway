package mx.quest.runway.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A site's region-language bundle as the Messages editor names it ({@code messages_pt-BR.properties}) is found;
 * Spring alone looks only for {@code messages_pt_BR}, so on its own those translations would never show.
 */
class LanguageTagBundlesTest {

    private static SiteAwareMessageSource.LanguageTagBundles source(Path dir) {
        SiteAwareMessageSource.LanguageTagBundles source = new SiteAwareMessageSource.LanguageTagBundles();
        source.setBasename("file:" + dir.resolve("messages"));
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static void write(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void theEditorsRegionFileIsFoundBeforeTheLanguageAndTheBase(@TempDir Path dir) throws Exception {
        write(dir.resolve("messages.properties"), "greeting=Hello\nonly.base=B\n");
        write(dir.resolve("messages_pt.properties"), "greeting=Olá (pt)\nonly.pt=P\n");
        write(dir.resolve("messages_pt-BR.properties"), "greeting=Olá (Brasil)\n");
        var source = source(dir);
        Locale brazil = Locale.forLanguageTag("pt-BR");
        assertEquals("Olá (Brasil)", source.getMessage("greeting", null, brazil));
        assertEquals("P", source.getMessage("only.pt", null, brazil), "then the language's own file");
        assertEquals("B", source.getMessage("only.base", null, brazil), "then the base");
        assertEquals("Olá (pt)", source.getMessage("greeting", null, Locale.forLanguageTag("pt")));

        // The defect this fixes: Spring's own reader skips the editor's file and answers with the language's.
        var plain = new org.springframework.context.support.ReloadableResourceBundleMessageSource();
        plain.setBasename("file:" + dir.resolve("messages"));
        plain.setDefaultEncoding("UTF-8");
        plain.setFallbackToSystemLocale(false);
        assertEquals("Olá (pt)", plain.getMessage("greeting", null, brazil));
    }

    @Test
    void aHandMadeUnderscoreFileStillWorks(@TempDir Path dir) throws Exception {
        write(dir.resolve("messages.properties"), "greeting=Hello\n");
        write(dir.resolve("messages_pt_BR.properties"), "greeting=Oi\n");
        assertEquals("Oi", source(dir).getMessage("greeting", null, Locale.forLanguageTag("pt-BR")));
    }
}
