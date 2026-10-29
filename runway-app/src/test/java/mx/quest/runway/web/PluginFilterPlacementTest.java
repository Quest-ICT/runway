package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.spi.FilterBand;
import org.junit.jupiter.api.Test;

/** A plugin can never escape its band: the floor clamp and the inner-of-security guarantee hold for any offset. */
class PluginFilterPlacementTest {

    @Test
    void offsetZeroIsTheBandsOuterEdge() {
        for (FilterBand band : FilterBand.values()) {
            if (band.contributable()) {
                assertEquals(PluginFilterPlacement.window(band).outer(),
                        PluginFilterPlacement.resolve(band, 0), band + " offset 0");
            }
        }
    }

    @Test
    void anEdgeIngressPluginCanNeverPrecedeTheProtectionFloor() {
        // Any offset — even hugely negative — clamps to CORE_PROTECTION + 1, never to the floor itself.
        assertEquals(FilterOrder.CORE_PROTECTION + 1, PluginFilterPlacement.resolve(FilterBand.EDGE_INGRESS, 0));
        assertEquals(FilterOrder.CORE_PROTECTION + 1,
                PluginFilterPlacement.resolve(FilterBand.EDGE_INGRESS, Integer.MIN_VALUE));
        assertTrue(PluginFilterPlacement.resolve(FilterBand.EDGE_INGRESS, -5) > FilterOrder.CORE_PROTECTION);
        assertTrue(PluginFilterPlacement.resolve(FilterBand.EDGE_INGRESS, 50) > FilterOrder.CORE_FORWARDED);
    }

    @Test
    void anApplicationPluginAlwaysRunsInnerOfSecurity() {
        // The whole point of bands: a feature filter can never preempt authentication.
        for (int offset : new int[] {Integer.MIN_VALUE, -1000, 0, 5, 999_000, Integer.MAX_VALUE}) {
            int order = PluginFilterPlacement.resolve(FilterBand.APPLICATION, offset);
            assertTrue(order > FilterOrder.CORE_SECURITY, "APPLICATION offset " + offset + " escaped inner-of-security");
            assertTrue(order > FilterOrder.CORE_ACCESS_LOG, "APPLICATION offset " + offset + " escaped the access log");
        }
    }

    @Test
    void onlyAnIngressPluginFilterSitsOuterOfTheAccessLog() {
        // Ingress filters turn abuse away like the floor and stay out of the access log with it; whatever any other
        // plugin filter answers is logged.
        for (FilterBand band : FilterBand.values()) {
            if (!band.contributable()) {
                continue;
            }
            for (int offset : new int[] {Integer.MIN_VALUE, -1000, 0, 5, 999_000, Integer.MAX_VALUE}) {
                int order = PluginFilterPlacement.resolve(band, offset);
                assertTrue(band == FilterBand.EDGE_INGRESS ? order < FilterOrder.CORE_ACCESS_LOG
                        : order > FilterOrder.CORE_ACCESS_LOG, band + " offset " + offset + " -> " + order);
            }
        }
    }

    @Test
    void everyContributableBandClampsWithinItsWindow() {
        for (FilterBand band : FilterBand.values()) {
            if (!band.contributable()) {
                continue;
            }
            PluginFilterPlacement.Window window = PluginFilterPlacement.window(band);
            for (int offset : new int[] {Integer.MIN_VALUE, -1, 0, 1, 1_000_000, Integer.MAX_VALUE}) {
                int order = PluginFilterPlacement.resolve(band, offset);
                assertTrue(order >= window.outer() && order <= window.inner(),
                        band + " offset " + offset + " escaped its window [" + window.outer() + "," + window.inner() + "]");
            }
        }
    }

    @Test
    void aLargerOffsetMovesAFilterInner() {
        assertTrue(PluginFilterPlacement.resolve(FilterBand.APPLICATION, 10)
                < PluginFilterPlacement.resolve(FilterBand.APPLICATION, 20));
    }

    @Test
    void theSecurityBandIsNotPluginReachable() {
        assertThrows(IllegalArgumentException.class, () -> PluginFilterPlacement.resolve(FilterBand.SECURITY, 0));
    }
}
