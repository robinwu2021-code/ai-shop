package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.trade.order.points.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import javax.sql.DataSource;

/** Real host Mapper/Outbox/transaction wiring; synthetic source identities only. */
@SpringBootTest
@ActiveProfiles("test")
class PointsOrderSourceBootTest {
    @Autowired OrderPointsCoordinator coordinator;
    @Autowired ai.neargo.shop.pay.points.event.PointsEventWorker worker;
    @Autowired ai.neargo.shop.pay.points.store.PointsReadStore payRead;

    @Autowired
    @Qualifier("payDataSource")
    DataSource payData;

    @Test
    void accepted_pay_event_survives_ack_loss_without_duplicate_kernel_delivery() {
        var fixture = new PointsOrderSourceOutboxTest();
        fixture.journal = journal;
        fixture.bindings = bindings;
        fixture.policies = policies;
        fixture.data = data;
        fixture.schema();
        PointsPostingDatabaseTest.initializeSchema(payData);
        var paySql = new JdbcTemplate(payData);
        if (paySql.queryForObject(
                        "SELECT COUNT(*) FROM pts_point_type WHERE type_code='TYPE'", Long.class)
                == 0) {
            paySql.update(
                    "INSERT INTO"
                        + " pts_point_type(type_code,market,currency,rate_points,rate_minor,status,deduct_ratio_bp,idle_days)"
                        + " VALUES('TYPE','CN','CNY',2,1,'DRAFT',2000,30)");
            paySql.update("INSERT INTO pts_fund_pool(type_code,currency) VALUES('TYPE','CNY')");
        }
        var draft = fixture.draft(UUID.randomUUID().toString());
        var raw = draft.fact().lifecycle();
        var signed =
                new ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact(
                        raw.eventNo(),
                        ai.neargo.shop.pay.points.port.PointsFactPortImpl.digest(raw),
                        raw.subOrderNo(),
                        raw.userNo(),
                        raw.typeCode(),
                        raw.sourceVersion(),
                        raw.kind(),
                        raw.finalGoodsMinor(),
                        raw.refundedGoodsMinor(),
                        raw.lines(),
                        raw.occurredAt());
        var event =
                new ai.neargo.shop.spi.points.PointsOrderEvent(
                        draft.orderNo(),
                        draft.subOrderNo(),
                        draft.merchantNo(),
                        draft.storeNo(),
                        draft.userNo(),
                        draft.typeCode(),
                        draft.currency(),
                        draft.sourceVersion(),
                        draft.occurredAt(),
                        new ai.neargo.shop.spi.points.SourceFactsPort.Fact(
                                0, signed, null, null, null, null));
        new TransactionTemplate(sourceTx).executeWithoutResult(s -> journal.append(event));
        var acknowledgement =
                mock(
                        OrderPointsFactService.class,
                        org.mockito.AdditionalAnswers.delegatesTo(journal));
        var firstAck = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            if (firstAck.getAndSet(false))
                                throw new IllegalStateException("ACK_LOST");
                            journal.acknowledge(event.eventNo());
                            return null;
                        })
                .when(acknowledgement)
                .acknowledge(event.eventNo());
        var consumer = new OrderPointsOutboxConsumer(acknowledgement, coordinator);
        assertThatThrownBy(() -> consumer.deliver(event.eventNo(), event.subOrderNo()))
                .hasMessageContaining("ACK_LOST");
        assertThat(journal.settledSubOrder(event.subOrderNo())).isFalse();
        consumer.deliver(event.eventNo(), event.subOrderNo());
        assertThat(
                        paySql.queryForObject(
                                "SELECT COUNT(*) FROM pts_outbox WHERE event_no=?",
                                Long.class,
                                "SOURCE:" + event.eventNo()))
                .isEqualTo(1);
        assertThat(worker.drain(event.typeCode(), 10, System.currentTimeMillis())).isEqualTo(1);
        assertThat(payRead.lifecycle(event.typeCode(), event.subOrderNo())).isNotNull();
        assertThat(journal.settledSubOrder(event.subOrderNo())).isTrue();
    }

    @Autowired OrderPointsFactService journal;
    @Autowired OrderPointsBindingService bindings;
    @Autowired PointsIntegrationPolicyService policies;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager sourceTx;

    @Autowired
    @Qualifier("dataSource")
    DataSource data;

    @Test
    void source_and_platform_outbox_commit_and_rollback_together() {
        var fixture = new PointsOrderSourceOutboxTest();
        fixture.journal = journal;
        fixture.bindings = bindings;
        fixture.policies = policies;
        fixture.data = data;
        fixture.schema();
        var event = fixture.draft(UUID.randomUUID().toString());
        var tx = new TransactionTemplate(sourceTx);
        var sql = new JdbcTemplate(data);
        tx.executeWithoutResult(
                s -> {
                    journal.append(event);
                    s.setRollbackOnly();
                });
        assertThat(journal.find(event.eventNo())).isEmpty();
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=?",
                                Long.class,
                                event.subOrderNo()))
                .isZero();
        tx.executeWithoutResult(s -> journal.append(event));
        tx.executeWithoutResult(s -> journal.append(event));
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=? AND"
                                        + " event_type='ORDER_POINTS_SOURCE_FACT'",
                                Long.class,
                                event.subOrderNo()))
                .isEqualTo(1);
        // This test verifies atomic persistence only; its deliberately unsigned envelope must
        // not leak into another scenario's real dispatcher on the shared H2 database.
        sql.update("DELETE FROM sys_outbox WHERE aggregate_id=? AND event_type='ORDER_POINTS_SOURCE_FACT'",event.subOrderNo());
    }
}
