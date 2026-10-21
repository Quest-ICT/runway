package mx.quest.runway.icon;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Set;
import mx.quest.runway.site.SiteChangedEvent;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantIconSeeder}: seeds the platform default favicon on a fresh site, never clobbers a site that
 * already owns an icon, and is best-effort when generation fails.
 */
class TenantIconSeederTest {

    private final SiteIconService icons = mock(SiteIconService.class);
    private final IconGenerationService generator = mock(IconGenerationService.class);
    private final TenantIconSeeder seeder = new TenantIconSeeder(generator, icons);

    @Test
    void seedsTheDefaultWhenTheSiteHasNoIcons() throws Exception {
        when(icons.presentSlots("acme")).thenReturn(Set.of());
        seeder.seedIfAbsent("acme");
        verify(generator).generateFrom(eq("acme"), any());
    }

    @Test
    void neverClobbersASiteThatAlreadyOwnsAnIcon() {
        when(icons.presentSlots("acme")).thenReturn(Set.of(SiteIcon.SVG_FAVICON));
        seeder.seedIfAbsent("acme");
        verifyNoInteractions(generator);
    }

    @Test
    void isBestEffortWhenGenerationFails() throws Exception {
        when(icons.presentSlots("acme")).thenReturn(Set.of());
        when(generator.generateFrom(eq("acme"), any())).thenThrow(new IOException("boom"));
        assertDoesNotThrow(() -> seeder.seedIfAbsent("acme"));
    }

    @Test
    void seedsOnAFreshCreatedEvent() throws Exception {
        when(icons.presentSlots("acme")).thenReturn(Set.of());
        seeder.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.CREATED));
        verify(generator).generateFrom(eq("acme"), any());
    }

    @Test
    void doesNotSeedForABackupImport() {
        // a site imported from a backup must stay a faithful copy — never gain a default the source lacked
        seeder.onSiteChanged(SiteChangedEvent.createdFromBackup("acme"));
        verifyNoInteractions(generator, icons);
    }
}
