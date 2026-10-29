package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.support.OrderReceiptFixture;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** A persisted managed authority must stop the real shared completion CAS and event writer. */
@SpringBootTest(
        properties =
                "spring.datasource.url=${CHECKOUT_NATIVE_MYSQL_URL:jdbc:h2:mem:checkout-managed;"
                        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@ActiveProfiles("test")
class ManagedServiceCompletionFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StatusCasMapper mapper;
    @Autowired private TradeStatusWriter writer;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void directCasCannotCompletePersistedManagedOrder() {
        String sub = seed(true);
        int rows =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status ->
                                        DataScopeContext.executeWithoutScope(
                                                () ->
                                                        mapper.casOrder(
                                                                sub,
                                                                "FULFILLING",
                                                                "COMPLETED",
                                                                LocalDateTime.now())));
        assertThat(rows)
                .as("managed source authority must be enforced at the lowest SQL CAS")
                .isZero();
        unchanged(sub);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderEvent.class,
            names = {
                "VERIFIED",
                "DELIVERED_BY_MERCHANT",
                "RECEIPT_CONFIRMED",
                "RECEIPT_AUTO_CONFIRMED",
                "INTERVENE"
            })
    void sharedWriterRejectsEveryManagedCompletionEvent(OrderEvent event) {
        String sub = seed(true);
        assertThatThrownBy(() -> complete(sub, event)).isInstanceOf(BizException.class);
        unchanged(sub);
    }

    @Test
    void legacyUnmanagedServiceStillCompletes() {
        String sub = seed(false);
        complete(sub, OrderEvent.VERIFIED);
        assertThat(status(sub)).isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT completed_at FROM ord_sub_order WHERE sub_order_no=?",
                                Long.class,
                                sub))
                .isNotNull();
        assertThat(completionLogs(sub)).isEqualTo(1);
    }

    private void complete(String sub, OrderEvent event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                DataScopeContext.executeWithoutScope(
                                        () -> {
                                            var current = mapper.lockOrder(sub);
                                            Actor actor =
                                                    switch (event) {
                                                        case RECEIPT_CONFIRMED -> Actor.BUYER;
                                                        case RECEIPT_AUTO_CONFIRMED -> Actor.SYSTEM;
                                                        case INTERVENE -> Actor.PLATFORM;
                                                        default -> Actor.MERCHANT;
                                                    };
                                            var facts =
                                                    OrderContext.authorizedOnly()
                                                            .withServiceClass(true)
                                                            .withAutoReceiptDue(true)
                                                            .withIntervene(
                                                                    OrderStatus.COMPLETED, true);
                                            writer.order(
                                                    current,
                                                    event,
                                                    actor,
                                                    "checkout-test",
                                                    "Complete service",
                                                    facts);
                                        }));
    }

    private String seed(boolean managed) {
        String sub = OrderReceiptFixture.seed(jdbc, "checkout-buyer");
        jdbc.update(
                "UPDATE ord_sub_order SET fulfillment='STORE_VERIFY',status='FULFILLING',"
                        + "fulfillment_authority=?,source_operation_no=? WHERE sub_order_no=?",
                managed ? "SERVICE_EXECUTION" : null,
                managed ? "op-" + sub : null,
                sub);
        return sub;
    }

    private void unchanged(String sub) {
        assertThat(status(sub)).isEqualTo("FULFILLING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT completed_at FROM ord_sub_order WHERE sub_order_no=?",
                                Long.class,
                                sub))
                .isNull();
        assertThat(completionLogs(sub)).isZero();
    }

    private String status(String sub) {
        return jdbc.queryForObject(
                "SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub);
    }

    private long completionLogs(String sub) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ord_status_log WHERE sub_order_no=? "
                        + "AND aggregate_type='ORDER' AND status='COMPLETED'",
                Long.class,
                sub);
    }
}
