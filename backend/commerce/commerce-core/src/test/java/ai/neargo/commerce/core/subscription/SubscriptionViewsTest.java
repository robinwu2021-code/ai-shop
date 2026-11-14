package ai.neargo.commerce.core.subscription;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.core.spi.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionViewsTest {
    @Test
    void currentPlanFollowsServerTimeAndIgnoresFutureRevokedAndIndependentSources() {
        var store = new StoreRef("MAIN", "S");
        var periods = List.of(
                period("free", "FREE", "free-v1", "FREE", 100, null, "NORMAL"),
                period("paid", "ORDER", "pro-v1", "PRO", 150, 150L, "NORMAL"),
                period("future", "ORDER", "pro-v2", "PRO", 400, 100L, "NORMAL"),
                period("revoked", "GRANT", "chain-v1", "CHAIN", 100, 500L, "REVOKED"),
                period("end", "GRANT", null, null, 100, 500L, "NORMAL"));
        var repo = (SubscriptionRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SubscriptionRepository.class}, (p, m, a) -> switch (m.getName()) {
                    case "lock" -> new AccountSnapshot(store, "merchant", "RETAIL", 7, null, 1);
                    case "periods" -> periods;
                    case "segments" -> List.of();
                    default -> throw new AssertionError(m.getName());
                });
        assertEquals("free-v1", SubscriptionViews.current(repo, store, 149).currentPlanVersionNo());
        var paid = SubscriptionViews.current(repo, store, 150);
        assertEquals("pro-v1", paid.currentPlanVersionNo());
        assertEquals("RETAIL", paid.businessTypeCode());
        assertEquals("TWD", paid.currency());
        assertEquals(150, paid.serverNow());
        assertEquals("free-v1", SubscriptionViews.current(repo, store, 300).currentPlanVersionNo());
        assertEquals("pro-v2", SubscriptionViews.current(repo, store, 400).currentPlanVersionNo());
        assertEquals("free-v1", SubscriptionViews.current(repo, store, 500).currentPlanVersionNo());
    }

    private static Period period(String no, String source, String version, String tier, long start, Long duration, String state) {
        return new Period(no, no, source, version == null ? null : "PLAN:" + tier, version, tier,
                100, start, duration, Map.of("POS", "POS"), 0, 0, "TWD", "p1", false, "REVOKED".equals(state) ? start : null,
                "REVOKED".equals(state) ? duration : 0, state,
                "REVOKED".equals(state) ? new SourceRevocation("revoke", start, 0, duration, 0, 0, "ops", "test") : null);
    }
}
