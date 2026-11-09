package ai.neargo.commerce.starter.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** TDD-B端门店功能矩阵与方案准入 AC8：两个只读端点的通道登记。 */
class StoreFeatureRoutePolicyTest {
    private final CommerceRoutePolicy policy = new CommerceRoutePolicy();

    @Test
    void featureMatrixIsAnAccountRead() {
        var route = policy.match("GET", "/biz/business-types/FNB/feature-matrix");
        assertEquals(CommerceRoutePolicy.Lane.ACCOUNT, route.lane());
        assertEquals(
                CommerceRoutePolicy.Lane.DENY,
                policy.match("POST", "/biz/business-types/FNB/feature-matrix").lane());
        assertEquals(
                CommerceRoutePolicy.Lane.DENY,
                policy.match("GET", "/biz/business-types/FNB/feature-matrix/x").lane());
    }

    @Test
    void storeFeaturesIsAStoreBootstrapReadLikeContext() {
        var route = policy.match("GET", "/biz/stores/S1/features");
        assertEquals(CommerceRoutePolicy.Lane.BOOTSTRAP, route.lane());
        assertEquals("S1", route.pathStore());
        assertNull(route.entitlement());
        assertTrue(route.foundation());
        assertEquals(
                CommerceRoutePolicy.Lane.DENY, policy.match("POST", "/biz/stores/S1/features").lane());
        assertEquals(
                CommerceRoutePolicy.Lane.DENY,
                policy.match("GET", "/biz/stores/S1%2Fother/features").lane());
    }
}
