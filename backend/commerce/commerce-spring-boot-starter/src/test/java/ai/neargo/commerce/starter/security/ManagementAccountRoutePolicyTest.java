package ai.neargo.commerce.starter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManagementAccountRoutePolicyTest {
    private final CommerceRoutePolicy policy = new CommerceRoutePolicy();

    @Test
    void existingManagementEndpointsUseAccountWithoutStoreTarget() {
        for (String endpoint : List.of(
                "GET /biz/management/context",
                "POST /biz/management/spaces",
                "GET /biz/management/spaces/M1/resources",
                "GET /biz/management/spaces/M1/recipients",
                "GET /biz/management/spaces/M1/entities",
                "GET /biz/management/spaces/M1/stores",
                "POST /biz/management/spaces/M1/grants",
                "DELETE /biz/management/spaces/M1/grants/G1",
                "GET /biz/management/brands",
                "POST /biz/management/brands",
                "GET /biz/management/brands/B1",
                "PUT /biz/management/brands/B1",
                "POST /biz/management/brands/B1/status-changes",
                "GET /biz/management/brands/B1/stores",
                "GET /biz/management/brands/B1/audit-events",
                "GET /biz/management/brands/B1/legal-entity-candidates",
                "PUT /biz/management/brands/B1/legal-entity",
                "GET /biz/management/brands/B1/legal-entity/commands",
                "GET /biz/management/brands/B1/marketing/context",
                "POST /biz/management/brands/B1/marketing/commands",
                "GET /biz/stores/S1/operating-context",
                "POST /biz/stores/S1/brand-changes/check",
                "POST /biz/stores/S1/brand-changes",
                "GET /biz/management/brand-changes/C1",
                "POST /biz/management/brand-changes/C1/decisions",
                "GET /biz/management/entities/E1/payment-merchants")) {
            String[] parts = endpoint.split(" ");
            var route = policy.match(parts[0], parts[1]);
            assertEquals(CommerceRoutePolicy.Lane.ACCOUNT, route.lane(), endpoint);
            assertNull(route.pathStore(), endpoint);
            assertNull(route.capability(), endpoint);
            assertTrue(policy.allowsAccountRecovery(route, parts[0], parts[1], null, "OTHER"), endpoint);
        }
    }

    @Test
    void unknownManagementPathsAndMethodsRemainDenied() {
        for (String endpoint : List.of(
                "POST /biz/management/context",
                "GET /biz/management/spaces",
                "DELETE /biz/management/spaces/M1/entities",
                "POST /biz/management/spaces/M1/resources",
                "GET /biz/management/spaces/M1/grants",
                "POST /biz/management/spaces/M1/grants/G1",
                "GET /biz/management/spaces/M1/unknown",
                "DELETE /biz/management/brands/B1",
                "POST /biz/management/brands/B1/stores",
                "GET /biz/management/brands/B1/status-changes",
                "GET /biz/management/brands/B1/unknown",
                "POST /biz/management/brands/B1/legal-entity",
                "DELETE /biz/management/brands/B1/marketing/commands",
                "PUT /biz/stores/S1/operating-context",
                "GET /biz/stores/S1/brand-changes/check",
                "POST /biz/stores/S1/brand-changes/unknown",
                "POST /biz/management/brand-changes/C1",
                "GET /biz/management/brand-changes/C1/decisions",
                "POST /biz/management/entities/E1/payment-merchants",
                "GET /biz/management/entities/E1/payment-merchants/unknown",
                "GET /biz/management/brands/B1/",
                "GET /biz/management/brands/" + "b".repeat(65),
                "GET /biz/ops/chain")) {
            String[] parts = endpoint.split(" ");
            assertEquals(CommerceRoutePolicy.Lane.DENY, policy.match(parts[0], parts[1]).lane(), endpoint);
        }
    }

    @Test
    void unrelatedStoreEndpointsRetainTheirOriginalLaneAndStoreTarget() {
        var store = policy.match("GET", "/biz/stores/S1/context");
        assertEquals(CommerceRoutePolicy.Lane.BOOTSTRAP, store.lane());
        assertEquals("S1", store.pathStore());
        var setting = policy.match("PUT", "/biz/store/S1/pay-setting");
        assertEquals(CommerceRoutePolicy.Lane.END_PROTECTED, setting.lane());
        assertEquals("S1", setting.pathStore());
        assertEquals("biz:store:admin", setting.capability());
        var memberPolicy = new CommerceRoutePolicy(List.of(
                CommerceRoutePolicy.HostRoute.of("GET", "/biz/stores/[A-Za-z0-9_-]{1,64}/receivables/R1")));
        var member = memberPolicy.match("GET", "/biz/stores/S1/receivables/R1");
        assertEquals(CommerceRoutePolicy.Lane.BOOTSTRAP, member.lane());
        assertEquals("S1", member.pathStore());
    }
}
