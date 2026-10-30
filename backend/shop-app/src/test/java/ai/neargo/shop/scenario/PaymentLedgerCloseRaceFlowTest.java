package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A delayed failure may close an attempt after the same channel has already confirmed its cash. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=${order.phase1.payment.test.url:jdbc:h2:mem:payment-close-race;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
        })
@ActiveProfiles("test")
class PaymentLedgerCloseRaceFlowTest {
    @Autowired private SettlePort settlement;
    @MockitoSpyBean private TestPayGateway gateway;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettleMappers.PaymentMapper payments;
    @Autowired @Qualifier("payTxManager") private PlatformTransactionManager manager;

    @Test
    void unconfirmedPrepayCannotCloseOrAllocateAnotherOriginalNumber() {
        String orderNo = "PAY-UNKNOWN-" + UUID.randomUUID();
        var command =
                new SettlePort.PaymentOpen(
                        orderNo, "UNKNOWN-BUYER", "M0001", "TEST", 100, "lost gateway response");
        var sends = new AtomicInteger();
        Mockito.doAnswer(
                        call -> {
                            sends.incrementAndGet();
                            call.callRealMethod();
                            return PayGateway.PrepayResult.fail("response lost after acceptance");
                        })
                .when(gateway)
                .prepay(ArgumentMatchers.any());
        try {
            var first = settlement.initPayment(command);
            assertThat(first.success()).isFalse();
            assertThat(payment(first.outTradeNo()).getStatus()).isEqualTo(StlPayment.PENDING);
            var repeated = settlement.initPayment(command);
            assertThat(repeated.outTradeNo()).isEqualTo(first.outTradeNo());
            assertThat(sends.get()).isEqualTo(1);
            assertThat(
                            DataScopeContext.executeWithoutScope(
                                    () ->
                                            payments.selectCount(
                                                    Wrappers.<StlPayment>lambdaQuery()
                                                            .eq(StlPayment::getOrderNo, orderNo)
                                                            .eq(
                                                                    StlPayment::getDirection,
                                                                    StlPayment.PAY))))
                    .isEqualTo(1L);
        } finally {
            Mockito.reset(gateway);
        }
    }

    @Test
    void staleFailureCannotCloseSuccessfulCash() throws Exception {
        String orderNo = "PAY-CLOSE-" + UUID.randomUUID();
        String ref = ledger.open(new SettlePort.PaymentOpen(orderNo, "CASH-RACE-BUYER", "M0001",
                "TEST", 100, "confirmed cash race"));
        CountDownLatch snapshot = new CountDownLatch(1);
        CountDownLatch confirmed = new CountDownLatch(1);
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var threads = Executors.newSingleThreadExecutor()) {
            var failure = threads.submit(() -> transaction.executeWithoutResult(tx -> {
                StlPayment pending = payment(ref);
                assertThat(pending.getStatus()).isEqualTo(StlPayment.PENDING);
                snapshot.countDown();
                await(confirmed);
                ledger.close(ref, "late initiation timeout");
            }));
            assertThat(snapshot.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(ledger.settle(new SettlePort.PaymentSettled(ref, "TEST", "TRADE-" + ref,
                        100, System.currentTimeMillis()))).isEqualTo(orderNo);
            } finally {
                confirmed.countDown();
            }
            failure.get(20, TimeUnit.SECONDS);
        }
        assertThat(payment(ref).getStatus()).isEqualTo(StlPayment.SUCCESS);
        assertThat(payment(ref).getAmountMinor()).isEqualTo(100);
    }

    private StlPayment payment(String ref) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOutTradeNo, ref)
                        .eq(StlPayment::getDirection, StlPayment.PAY)));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
