package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Persistent counters and the same pay transaction used by future grants, without mapper mocks. */
@SpringBootTest(properties = {"shop.pay.entitlement.catalog.enabled=true", "shop.pay.entitlement.issue.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-issue-policy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementIssuePolicyFlowTest {
    @Autowired EntitlementCatalogService catalog;
    @Autowired EntitlementIssuePolicyService policies;
    @Autowired @Qualifier("payTxManager") PlatformTransactionManager manager;
    private final CatalogScope scope = new CatalogScope("MAIN", "ISS-" + UUID.randomUUID(), "POLICY-TEST", false);
    private String key() { return UUID.randomUUID().toString(); }
    private String template() {
        var definition = new EntitlementDefinition("coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("CENTER", "TARGETED"));
        var head = catalog.create(scope, definition, key());
        catalog.publish(scope, head.templateNo(), head.revision(), key());
        return head.templateNo();
    }
    private TransactionTemplate tx() { return new TransactionTemplate(manager); }
    @Test void configReplaysAndCannotLowerGlobalOrPersonalCapacityBelowPromises() {
        String no = template(); String k = key(); var cmd = new SavePolicy(0, 10L, 1000L, 2, true);
        var first = policies.save(scope, no, cmd, k);
        tx().executeWithoutResult(status -> policies.reserve(scope, no, "U1", 2, 200));
        assertThat(policies.save(scope, no, cmd, k)).isEqualTo(first);
        var current = policies.get(scope, no);
        assertThatThrownBy(() -> policies.save(scope, no,
                new SavePolicy(current.revision(), 1L, 1000L, 2, true), key())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> policies.save(scope, no,
                new SavePolicy(current.revision(), 10L, 100L, 2, true), key())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> policies.save(scope, no,
                new SavePolicy(current.revision(), 10L, 1000L, 1, true), key())).isInstanceOf(BizException.class);
        var disabled = policies.save(scope, no, new SavePolicy(current.revision(), 10L, 1000L, 2, false), key());
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> policies.reserve(scope, no, "U2", 1, 100)))
                .isInstanceOf(BizException.class);
        tx().executeWithoutResult(status -> policies.settle(scope, no, "U1", 2, 200, true));
        assertThat(policies.get(scope, no).issuedCount()).isEqualTo(2);
        assertThat(policies.get(scope, no).heldCount()).isZero();
        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> policies.get(new CatalogScope("MAIN", "OTHER", "X", false), no))
                .isInstanceOf(BizException.class);
    }
    @Test void failedPersonalReservationAndBusinessFailureRollBackBothCounters() {
        String no = template(); policies.save(scope, no, new SavePolicy(0, 10L, 1000L, 1, true), key());
        tx().executeWithoutResult(status -> policies.reserve(scope, no, "U1", 1, 100));
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> policies.reserve(scope, no, "U1", 1, 100)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            policies.reserve(scope, no, "U2", 1, 100); throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(policies.get(scope, no).heldCount()).isEqualTo(1);
        assertThat(policies.get(scope, no).heldExposureMinor()).isEqualTo(100);
        tx().executeWithoutResult(status -> policies.settle(scope, no, "U1", 1, 100, false));
        assertThat(policies.get(scope, no).heldCount()).isZero();
    }
    @Test void concurrentReservationsRespectGlobalAndPersonalLimits() throws Exception {
        String no = template(); policies.save(scope, no, new SavePolicy(0, 20L, 2000L, 2, true), key());
        try (var pool = Executors.newFixedThreadPool(10)) {
            var calls = new java.util.ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 100; i++) {
                String user = "U" + (i % 10);
                calls.add(() -> {
                    try {
                        tx().executeWithoutResult(status -> {
                            policies.reserve(scope, no, user, 1, 100);
                            policies.settle(scope, no, user, 1, 100, true);
                        }); return true;
                    } catch (BizException exhausted) { return false; }
                });
            }
            int succeeded = 0;
            for (var result : pool.invokeAll(calls)) if (result.get()) succeeded++;
            assertThat(succeeded).isEqualTo(20);
        }
        assertThat(policies.get(scope, no).issuedCount()).isEqualTo(20);
        assertThat(policies.get(scope, no).usedExposureMinor()).isEqualTo(2000);
        assertThat(policies.get(scope, no).heldCount()).isZero();
    }
}
