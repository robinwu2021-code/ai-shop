package ai.neargo.commerce.starter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetailEndRoutePolicyTest {
    @Test
    void retailUsesTheRegisteredEndCodeAndStockDuty() {
        var policy = new CommerceRoutePolicy();
        assertEquals("END_RETAIL", CommerceRoutePolicy.entitlementForEnd("END_RETAIL").orElseThrow());
        assertEquals("END_RETAIL", CommerceRoutePolicy.endCode("END_RETAIL"));
        assertEquals("biz:stock", policy.entryDuty("END_RETAIL"));
        assertTrue(CommerceRoutePolicy.entitlementForEnd("END_UNKNOWN").isEmpty());
    }
}
