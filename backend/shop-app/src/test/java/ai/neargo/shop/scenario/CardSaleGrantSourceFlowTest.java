package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.trade.entitlement.CardSaleSnapshotPlan;
import ai.neargo.shop.trade.entitlement.CardSaleSnapshotService;
import ai.neargo.shop.trade.port.CardSaleGrantSourcePortImpl;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:card-source-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class CardSaleGrantSourceFlowTest {
    @Autowired CardSaleSnapshotService snapshots;
    @Autowired CardSaleGrantSourcePortImpl sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("transactionManager")
    org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void cancellationDecisionIsAtomicWithTheOrderAndNeverInferredFromMissingPayment() {
        String no = UUID.randomUUID().toString().replace("-", "");
        var ctx = new EntitlementContext("MAIN", "OP1", no, "a".repeat(64));
        var plan = snapshots.capture(new CardSaleSnapshotPlan.OrderFacts(no, "U1", "CNY"), List.of(
                new CardSaleSnapshotPlan.SaleLine("OL" + no, "SUB1", "M1", "S1", "G1", "SKU1", 1000, 1,
                        List.of(new CardSaleSnapshotPlan.EntitlementRule("V1", "COUNT", "{\"total\":30}")))));
        var source = plan.getFirst().source();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,currency,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'U1',1000,'CNY','WAIT_PAY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", no);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE ord_order SET status='CANCELLED' WHERE order_no=?", no);
            assertThat(snapshots.recordCancelledFromOrder(no)).isEqualTo(1);
            throw new IllegalStateException("rollback proof");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback proof");
        assertThat(sources.decision(ctx, source).status()).isEqualTo(SourceDecisionPort.State.PENDING);
        jdbc.update("UPDATE ord_order SET status='CANCELLED' WHERE order_no=?", no);
        assertThat(sources.decision(ctx, source).status()).isEqualTo(SourceDecisionPort.State.PENDING);
        tx.executeWithoutResult(status -> assertThat(snapshots.recordCancelledFromOrder(no)).isEqualTo(1));
        var cancelled = sources.decision(ctx, source);
        assertThat(cancelled.status()).isEqualTo(SourceDecisionPort.State.CANCELLED);
        assertThat(cancelled.snapshotHash()).isEqualTo(plan.getFirst().snapshotHash());
        tx.executeWithoutResult(status -> snapshots.recordCancelledFromOrder(no));
        assertThat(sources.decision(ctx, source)).isEqualTo(cancelled);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=? WHERE order_no=?", 1L, no);
        assertThatThrownBy(() -> sources.decision(ctx, source)).isInstanceOf(BizException.class);
        jdbc.update("UPDATE ord_order SET pay_channel='WECHAT',pay_trade_no='PAY1' WHERE order_no=?", no);
        snapshots.recordPaidFromOrder(no);
        assertThatThrownBy(() -> sources.decision(ctx, source)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> sources.resolve(ctx, new GrantCommand("G1", source, null, "V1", "U1", "M1",
                1, plan.getFirst().snapshotHash(), "PAY1"))).isInstanceOf(BizException.class);
    }

    @Test void actualOrderPaymentStampsFourFrozenUnitsAndRepeatedNotificationKeepsOriginalTime() {
        String no = UUID.randomUUID().toString().replace("-", "");
        var ctx = new EntitlementContext("MAIN", "OP1", no, "a".repeat(64));
        var plan = snapshots.capture(new CardSaleSnapshotPlan.OrderFacts(no, "U1", "CNY"), List.of(
                new CardSaleSnapshotPlan.SaleLine("OL1", "SUB1", "M1", "S1", "G1", "SKU1", 12000, 2,
                        List.of(new CardSaleSnapshotPlan.EntitlementRule("V1", "COUNT", "{\"total\":30}"),
                                new CardSaleSnapshotPlan.EntitlementRule("V2", "COUNT", "{\"total\":1}")))));
        assertThat(plan).hasSize(4);
        assertThat(sources.decision(ctx, plan.getFirst().source()).status())
                .isEqualTo(SourceDecisionPort.State.PENDING);
        var at = Instant.parse("2026-10-19T01:00:00Z");
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,goods_amount,freight_amount,discount_amount,"
                + "currency,status,pay_channel,pay_trade_no,paid_at,tenant_no,created_at,updated_at) "
                + "VALUES (?,'U1',24000,24000,0,0,'CNY','PAID','WECHAT','PAY1',?,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", no, at.toEpochMilli());
        assertThat(snapshots.recordPaidFromOrder(no)).isEqualTo(4);
        assertThat(snapshots.recordPaidFromOrder(no)).isZero();
        for (var item : plan) {
            var command = new GrantCommand("G" + item.source().grantKey().substring(0, 60), item.source(), null,
                    item.rule().templateVersionNo(), "U1", "M1", 1, item.snapshotHash(), "PAY1");
            assertThat(sources.resolve(ctx, command).paidAt()).isEqualTo(at);
            var decision = sources.decision(ctx, item.source());
            assertThat(decision.status()).isEqualTo(SourceDecisionPort.State.COMMITTED);
            assertThat(decision.decision().occurredAt()).isEqualTo(at);
            assertThat(sources.decision(ctx, item.source())).isEqualTo(decision);
        }
        jdbc.update("UPDATE ord_order SET pay_channel='STORED_VALUE' WHERE order_no=?", no);
        assertThatThrownBy(() -> sources.decision(ctx, plan.getFirst().source())).isInstanceOf(BizException.class);
    }
}
