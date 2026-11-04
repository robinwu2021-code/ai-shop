package ai.neargo.commerce.core.mall;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UpgradeValueTest {
    @Test
    void exactRemainingValueIncludesPartialDaysAndFutureCycles() {
        assertEquals(20000, UpgradeValue.remaining(36500, 365L * 86400000, 165L * 86400000, false));
        assertEquals(
                19950,
                UpgradeValue.remaining(36500, 365L * 86400000, 165L * 86400000 + 43200000, false));
        assertEquals(36500, UpgradeValue.remaining(36500, 365L * 86400000, 0, false));
        assertEquals(0, UpgradeValue.remaining(36500, 365L * 86400000, 0, true));
        assertEquals(
                Long.MAX_VALUE - 1,
                UpgradeValue.remaining(Long.MAX_VALUE, Long.MAX_VALUE, 1, false));
    }

    @Test
    void recognizedPolicyOnlyAuthorizesUnusedDigital() {
        assertTrue(
                RefundPolicy.parse(
                                "{\"schema\":\"commerce-refund-v1\",\"unusedDigital\":\"FULL_ORIGINAL_ALLOCATION\",\"usedDigital\":\"MANUAL_ONLY\"}")
                        .automaticUnused());
        for (String raw :
                new String[] {
                    "{}",
                    "{\"schema\":\"unknown\"}",
                    "{\"schema\":\"commerce-refund-v1\",\"unusedDigital\":\"FULL_ORIGINAL_ALLOCATION\",\"usedDigital\":\"FULL\"}",
                    "{\"schema\":\"commerce-refund-v1\",\"schema\":\"commerce-refund-v1\",\"unusedDigital\":\"FULL_ORIGINAL_ALLOCATION\",\"usedDigital\":\"MANUAL_ONLY\"}"
                }) assertFalse(RefundPolicy.parse(raw).automaticUnused());
    }

    @Test
    void sourceCutsCannotRewriteTheOriginalDurationOrInventEffectiveTime() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ai.neargo.commerce.core.spi.Period(
                                "p",
                                "o",
                                "ORDER",
                                "plan",
                                "v",
                                "PRO",
                                10,
                                10,
                                100L,
                                java.util.Map.of("POS", "POS"),
                                100,
                                0,
                                "CNY",
                                "policy",
                                false,
                                80L,
                                50,
                                "CONVERTED"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ai.neargo.commerce.core.spi.Period(
                                "p",
                                "o",
                                "ORDER",
                                "plan",
                                "v",
                                "PRO",
                                10,
                                10,
                                100L,
                                java.util.Map.of("POS", "POS"),
                                100,
                                0,
                                "CNY",
                                "policy",
                                false,
                                null,
                                50,
                                "NORMAL"));
    }
}
