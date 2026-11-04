package ai.neargo.commerce.starter.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MallRecoveryRoutePolicyTest {
    @Test
    void exactStoreRecoveryRoutesAllowExpiredEndsButNotUnknownMethodsOrNamespaces() {
        var policy = new CommerceRoutePolicy();
        for (String path :
                java.util.List.of("orders", "orders/order.1:abc", "orders/order.1:abc/payments")) {
            var route = policy.match("GET", "/biz/stores/S/mall/" + path);
            assertEquals(CommerceRoutePolicy.Lane.BOOTSTRAP, route.lane());
            assertEquals(CommerceRoutePolicy.PROCUREMENT_DUTY, route.capability());
            assertNull(route.entitlement());
        }
        for (String path :
                java.util.List.of(
                        "orders/order.1:abc/payments/refresh",
                        "quotes",
                        "upgrade-quotes",
                        "orders",
                        "orders/order.1:abc/cancel",
                        "orders/order.1:abc/payments",
                        "orders/order.1:abc/refund-previews",
                        "orders/order.1:abc/refunds"))
            assertEquals(
                    CommerceRoutePolicy.Lane.BOOTSTRAP,
                    policy.match("POST", "/biz/stores/S/mall/" + path).lane());
        for (String path :
                java.util.List.of(
                        "/biz/stores/S/mall/orders/o/paid",
                        "/biz/stores/S/mall/orders/o/refunds/r/approve",
                        "/biz/store/S/mall/orders/o",
                        "/biz/stores/S/mall/orders/o%2Fpaid",
                        "/biz/stores/S%2Fother/mall/orders/o"))
            assertEquals(CommerceRoutePolicy.Lane.DENY, policy.match("POST", path).lane());
        assertEquals(
                CommerceRoutePolicy.Lane.DENY,
                policy.match("HEAD", "/biz/stores/S/mall/orders/order.1").lane());
        assertEquals(
                CommerceRoutePolicy.Lane.DENY,
                policy.match("DELETE", "/biz/stores/S/mall/orders/order.1").lane());
    }
}
