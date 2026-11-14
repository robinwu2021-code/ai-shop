package ai.neargo.shop.print;

import ai.neargo.shop.spi.printing.*;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.layout.LayoutProfileRegistry;

import org.junit.jupiter.api.Test;

class PrintFontInitializationTest {
    @Test
    void assemblingUnusedModuleDoesNotLoadFontOrMetrics() {
        int[] calls = {0};
        PrintFontMetricsPort metrics =
                (text, font, size) -> {
                    calls[0]++;
                    throw new AssertionError("Font should load only for an actual print layout");
                };
        assertDoesNotThrow(() -> new LayoutProfileRegistry(metrics));
        assertEquals(0, calls[0]);
    }
}
