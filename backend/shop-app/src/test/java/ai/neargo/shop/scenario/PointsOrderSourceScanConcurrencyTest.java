package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderSourceOutboxTest.Config.class)
class PointsOrderSourceScanConcurrencyTest {
    @Autowired OrderPointsFactService journal;
    @Autowired OrderPointsBindingService bindings;
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource data;

    @Test
    void low_sequence_uncommitted_transaction_cannot_be_overtaken_by_scan() throws Exception {
        try (var c = data.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().contains("MySQL"));
        }
        var fixture = new PointsOrderSourceOutboxTest();
        fixture.journal = journal;
        fixture.bindings = bindings;
        fixture.policies = policies;
        fixture.data = data;
        fixture.schema();
        var first = fixture.draft(UUID.randomUUID().toString());
        var second = fixture.draft(UUID.randomUUID().toString());
        var pool = Executors.newFixedThreadPool(2);
        var future = new AtomicReference<Future<PointsOrderEvent>>();
        var entered = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactionManager);
        try {
            var low =
                    tx.execute(
                            status -> {
                                var frozen = journal.append(first);
                                future.set(
                                        pool.submit(
                                                () -> {
                                                    entered.countDown();
                                                    return tx.execute(s -> journal.append(second));
                                                }));
                                try {
                                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                                assertThatThrownBy(
                                                () -> future.get().get(300, TimeUnit.MILLISECONDS))
                                        .isInstanceOf(TimeoutException.class);
                                // Separate source transaction/connection sees neither uncommitted
                                // fact.
                                var scan =
                                        pool.submit(
                                                () ->
                                                        journal.scan(
                                                                first.typeCode(),
                                                                frozen.fact().sequence() - 1,
                                                                10));
                                try {
                                    assertThat(scan.get(5, TimeUnit.SECONDS).facts()).isEmpty();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                return frozen;
                            });
            var high = future.get().get(10, TimeUnit.SECONDS);
            assertThat(high.fact().sequence()).isEqualTo(low.fact().sequence() + 1);
            assertThat(journal.scan(first.typeCode(), low.fact().sequence() - 1, 10).facts())
                    .containsExactly(low.fact(), high.fact());
        } finally {
            pool.shutdownNow();
        }
    }
}
