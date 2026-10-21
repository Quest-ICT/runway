package mx.quest.runway.wizard;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** WIZARD mode (no app-master pin): the open setup page is served and every other path funnels to it. */
@SpringBootTest
class WizardModeTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        // No runway.security.app-master-id → WIZARD mode (and no seeded master).
        Path external = Files.createTempDirectory("runway-wizard");
        registry.add("runway.storage.external", () -> external.toString());
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theSetupPageIsOpen() throws Exception {
        mvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Welcome to Runway")));
    }

    @Test
    void theSetupPageIsStyledWithTheSelfHostedChrome() throws Exception {
        String html = mvc.perform(get("/setup")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/__assets/vendor/tabler.min.css"), "Tabler CSS linked");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"form-control\""), "styled form controls");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("runway-chrome.js"), "chrome JS (password toggle) linked");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/__assets/runway.svg"), "the Runway logo/favicon");
    }

    @Autowired
    private mx.quest.runway.console.AssetVersion assetVersion;

    @Test
    void everyAssetTheSetupPagesReferenceIsVersioned() throws Exception {
        // The wizard hand-builds its HTML in Java, so the operator-screen guard cannot see it: check it here. (The
        // "setup complete, restarting" page shares the same document() wrapper; posting it would end the setup.)
        String form = mvc.perform(get("/setup")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(),
                mx.quest.runway.console.AssetVersionAudit.audit("/setup", form, assetVersion.value()));
        org.junit.jupiter.api.Assertions.assertEquals(6, mx.quest.runway.console.AssetVersionAudit.count(form),
                "favicon, Tabler, FontAwesome, chrome CSS, logo, chrome JS");
    }

    @Test
    void theChromeAssetsAreServedInWizardMode() throws Exception {
        // Without /__assets permitted, the styling links would 302 to /setup and the page would be unstyled.
        mvc.perform(get("/__assets/vendor/tabler.min.css")).andExpect(status().isOk());
        mvc.perform(get("/__assets/runway-chrome.js")).andExpect(status().isOk());
    }

    @Test
    void theHealthProbeIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void everyOtherPathFunnelsToSetup() throws Exception {
        for (String path : new String[] {"/__app", "/login", "/", "/about"}) {
            mvc.perform(get(path))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/setup"));
        }
    }
}
