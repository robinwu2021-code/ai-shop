package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.TemplateView;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared command handling must participate in the owner transaction, never commit a PENDING row alone. */
@SpringBootTest(properties = {
        "shop.pay.entitlement.catalog.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-catalog-on;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class EntitlementCommandDeduplicatorTest {
    @Autowired private EntitlementCommandDeduplicator commands;
    @Autowired private EntitlementCatalogService catalog;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("payTxManager") private PlatformTransactionManager manager;

    @Test
    void requiresAnExistingPayTransaction() {
        var scope = scope();
        assertThatThrownBy(() -> commands.execute(scope, "ISSUE_TEST", "key1", "body", "r1",
                String.class, () -> "success"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE request_key=?",
                Long.class, "key1")).isZero();
    }

    @Test
    void replaysOneNamespaceButKeepsOtherOperationsIndependent() {
        var scope = scope();
        var runs = new AtomicInteger();
        var tx = new TransactionTemplate(manager);
        String first = tx.execute(status -> commands.execute(scope, "ISSUE_TEST", "opaqueKey", "body", "r1",
                String.class, () -> "result-" + runs.incrementAndGet()));
        String replay = tx.execute(status -> commands.execute(scope, "ISSUE_TEST", "opaqueKey", "body", "r1",
                String.class, () -> "result-" + runs.incrementAndGet()));
        String other = tx.execute(status -> commands.execute(scope, "USE_TEST", "opaqueKey", "body", "r1",
                String.class, () -> "result-" + runs.incrementAndGet()));
        assertThat(first).isEqualTo(replay).isEqualTo("result-1");
        assertThat(other).isEqualTo("result-2");
        assertThat(runs).hasValue(2);
    }

    @Test
    void failureRollsBackNestedBusinessAndBothDedupRowsThenAllowsRetry() {
        var scope = scope();
        var tx = new TransactionTemplate(manager);
        String key = UUID.randomUUID().toString();
        String nestedKey = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tx.execute(status -> commands.execute(scope, "GRANT_TEST", key, "body", "r1",
                TemplateView.class, () -> {
                    catalog.create(scope, definition(), nestedKey);
                    throw new IllegalStateException("abort the owner transaction");
                }))).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.list(scope, null, null, 1, 20).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE request_key IN (?,?)",
                Long.class, key, nestedKey)).isZero();
        var result = tx.execute(status -> commands.execute(scope, "GRANT_TEST", key, "body", "r1",
                TemplateView.class, () -> catalog.create(scope, definition(), nestedKey)));
        assertThat(result).isNotNull();
        assertThat(catalog.list(scope, null, null, 1, 20).total()).isEqualTo(1);
    }

    private CatalogScope scope() {
        return new CatalogScope("DEDUP-" + UUID.randomUUID().toString().substring(0, 8),
                "ENTITY", "TEST_ACTOR", false);
    }

    private EntitlementDefinition definition() {
        return new EntitlementDefinition("Test coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 500L,
                null, null, 0L, null, "COUNT", 1, null,
                new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("ALL", List.of()), List.of("ACTIVITY"));
    }
}
