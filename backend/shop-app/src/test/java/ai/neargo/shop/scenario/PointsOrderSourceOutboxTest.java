package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.event.*;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.impl.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderSourceOutboxTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsOrderSourceOutboxTest {
    @Autowired OrderPointsFactService journal;
    @Autowired OrderPointsBindingService bindings;
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource data;
    @Autowired OutboxEventBus outbox;

    @BeforeAll
    void schema() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V453__order_points_binding.sql"),
                        new ClassPathResource("db/migration/V454__order_points_source_facts.sql"))
                .execute(data);
    }

    PointsOrderEvent draft(String suffix) {
        var fixture = new PointsOrderBindingTest();
        fixture.bindings = bindings;
        fixture.policies = policies;
        var store = fixture.activate();
        var b = bindings.admit(fixture.admission(store, "S:" + suffix));
        var a = b.admission();
        var at = Instant.parse("2026-10-18T00:00:00Z");
        var f =
                new PointsFactPort.LifecycleFact(
                        "EV:" + suffix,
                        "HASH",
                        a.subOrderNo(),
                        a.userNo(),
                        a.typeCode(),
                        1,
                        PointsFactPort.FactKind.CANCELLED,
                        100,
                        0,
                        List.of(new PointsFactPort.LineSnapshot("ITEM", 1, 100, 0)),
                        at);
        return new PointsOrderEvent(
                a.orderNo(),
                a.subOrderNo(),
                a.merchantNo(),
                a.storeNo(),
                a.userNo(),
                a.typeCode(),
                a.currency(),
                1,
                at,
                new SourceFactsPort.Fact(0, f, null, null, null, null));
    }

    @Test
    void journal_rejects_missing_source_transaction_and_rolls_back_with_business() {
        var event = draft(UUID.randomUUID().toString());
        assertThatThrownBy(() -> journal.append(event))
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class);
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(
                s -> {
                    journal.append(event);
                    s.setRollbackOnly();
                });
        assertThat(journal.find(event.eventNo())).isEmpty();
    }

    @Test
    void duplicate_returns_original_sequence_and_changed_body_is_rejected() {
        var event = draft(UUID.randomUUID().toString());
        var tx = new TransactionTemplate(transactionManager);
        var first = tx.execute(s -> journal.append(event));
        assertThat(first.fact().sequence()).isPositive();
        PointsOrderEvent replay = tx.execute(s -> journal.append(event));
        assertThat(replay).isEqualTo(first);
        var changed =
                new PointsOrderEvent(
                        event.orderNo(),
                        event.subOrderNo(),
                        event.merchantNo(),
                        event.storeNo(),
                        event.userNo(),
                        event.typeCode(),
                        "USD",
                        event.sourceVersion(),
                        event.occurredAt(),
                        event.fact());
        assertThatThrownBy(() -> tx.execute(s -> journal.append(changed)))
                .hasMessageContaining("CONFLICT");
        assertThat(journal.scan(event.typeCode(), first.fact().sequence() - 1, 1).facts())
                .containsExactly(first.fact());
        assertThat(journal.settledSubOrder(event.subOrderNo())).isFalse();
        journal.acknowledge(event.eventNo());
        assertThat(journal.settledSubOrder(event.subOrderNo())).isTrue();
    }

    @Test
    void failed_outbox_insert_rolls_back_counter_and_journal() {
        var event = draft(UUID.randomUUID().toString());
        doThrow(new IllegalStateException("OUTBOX_UNAVAILABLE")).when(outbox).publish(any());
        try {
            assertThatThrownBy(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .execute(s -> journal.append(event)))
                    .hasMessageContaining("OUTBOX_UNAVAILABLE");
            assertThat(journal.find(event.eventNo())).isEmpty();
        } finally {
            reset(outbox);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import({PointsOrderBindingTest.Config.class, OrderPointsFactServiceImpl.class})
    static class Config {
        @Bean
        OutboxEventBus outbox() {
            return mock(OutboxEventBus.class);
        }
    }
}
