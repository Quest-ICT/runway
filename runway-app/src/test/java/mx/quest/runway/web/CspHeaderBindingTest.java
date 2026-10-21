package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds the SHIPPED classpath {@code application.yml} to {@link RunwayCspProperties} — exactly the way
 * {@code @ConfigurationProperties} does at boot — and asserts the emitted policy. This closes the drift that
 * caused a shipped regression: {@link RunwayCspPropertiesTest} tests the Java <b>field defaults</b> (so it saw
 * {@code script-src 'self'} and passed), while the bundled {@code application.yml} still set
 * {@code script-src: "'self' 'unsafe-inline'"}, which binds <b>over</b> the field default — so the shipped header
 * kept {@code 'unsafe-inline'}. Testing the actual bound value keeps config and code from disagreeing again.
 */
class CspHeaderBindingTest {

    private static RunwayCspProperties boundFromApplicationYml() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources mps = new MutablePropertySources();
        sources.forEach(mps::addLast);
        return new Binder(ConfigurationPropertySources.from(mps))
                .bind("runway.csp", RunwayCspProperties.class)
                .orElseGet(RunwayCspProperties::new);
    }

    @Test
    void theShippedConfigDoesNotReintroduceUnsafeInlineOnScriptSrc() throws IOException {
        RunwayCspProperties props = boundFromApplicationYml();
        String policy = props.buildPolicy();
        String lead = RunwayCspProperties.NONCE_PLACEHOLDER + RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER;
        assertThat(policy)
                .as("the bundled application.yml must not re-add 'unsafe-inline' to script-src"
                        + " (the shipped-config regression)")
                .contains("script-src " + lead + "'self';")            // nonce + strict-dynamic placeholder + 'self'
                .doesNotContain(lead + "'self' 'unsafe-inline'");      // still no 'unsafe-inline' on script-src
        // The shipped yml keeps strict-dynamic on, so the bound policy carries the SD placeholder (CspFilter emits
        // the literal per surface). A yml that turned it off would drop the placeholder — this pins it on.
        assertThat(policy).contains(RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER);
        // style-src intentionally keeps 'unsafe-inline'; object-src is emitted; the report-only default holds.
        assertThat(policy).contains("style-src 'self' 'unsafe-inline'");
        assertThat(policy).contains("object-src 'none'");
        assertThat(props.getMode()).isEqualTo(RunwayCspProperties.Mode.REPORT);
    }

    /** The shipped yml binds public-strict-dynamic ON (the default) — the yml and the Java default agree. */
    @Test
    void theShippedConfigTurnsThePublicPageNonceOn() throws IOException {
        RunwayCspProperties props = boundFromApplicationYml();
        assertThat(props.isPublicStrictDynamic()).isTrue();
        assertThat(props.isPublicNonceActive()).isTrue();
    }
}
