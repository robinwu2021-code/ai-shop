package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/** Cash intent commits before channel dispatch, including ambiguous response replay. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:refund-dispatch;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RefundDispatchBoundaryTest {

    @Autowired
    private SettleService settleService;
    @Autowired
    private PaymentLedgerService ledger;
    @Autowired
    private SettleMappers.PaymentMapper paymentMapper;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    /**
     * 通道那一侧的账本。<b>夹具必须也在这边下单</b> ——
     * 只在我方落流水的话，退款时通道会说「原单不存在」，
     * 而那正是 {@code TestPayGateway} 刻意保留的严格：
     * 恒成功的假通道会让「给一笔没收到钱的单退款」在联调里永不暴露。
     */
    @MockitoSpyBean
    private ai.neargo.shop.pay.channel.TestPayGateway testChannel;
    @Autowired @Qualifier("transactionManager")
    private PlatformTransactionManager mainTx;

    private static int seq = 0;

    @Test
    void intent_is_saved_without_sending_and_dispatch_is_outside_transactions_once() {
        Fixture f = paidOrderWithBill("TEST");
        String no = settleService.prepareRefund(f.subOrderNo, "AS-" + f.subOrderNo, 1000, "refund");
        assertThat(refundRow(no).getTradeNo()).isNull();
        assertThatThrownBy(() -> new TransactionTemplate(mainTx).executeWithoutResult(
                status -> settleService.prepareRefund(f.subOrderNo, "AS-" + f.subOrderNo, 1000, "refund")))
                .hasMessage("PAY_REFUND_PREPARE_IN_TRANSACTION");
        assertThatThrownBy(() -> new TransactionTemplate(mainTx).executeWithoutResult(
                status -> settleService.dispatchRefund(no))).hasMessage("PAY_REFUND_DISPATCH_IN_TRANSACTION");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(refundRow(no).getRefundDispatchAt()).isNotNull();
            return call.callRealMethod();
        }).when(testChannel).refund(any(), anyLong(), anyString(), anyString());
        settleService.dispatchRefund(no);
        settleService.dispatchRefund(no);
        verify(testChannel, times(1)).refund(any(), anyLong(), anyString(), anyString());
        assertThat(refundRow(no).getStatus()).isEqualTo(StlPayment.PENDING);
    }

    @Test
    void lost_response_preserves_intent_and_never_submits_again() {
        Fixture f = paidOrderWithBill("TEST");
        String no = settleService.prepareRefund(f.subOrderNo, "AS-" + f.subOrderNo, 1000, "refund");
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost response"); })
                .when(testChannel).refund(any(), anyLong(), anyString(), anyString());
        settleService.dispatchRefund(no);
        settleService.dispatchRefund(no);
        verify(testChannel, times(1)).refund(any(), anyLong(), anyString(), anyString());
        assertThat(refundRow(no).getStatus()).isEqualTo(StlPayment.PENDING);
        assertThat(refundRow(no).getRefundDispatchAt()).isNotNull();
        var proof = testChannel.queryRefund(refundRow(no).getOutTradeNo());
        assertThat(proof.found()).isTrue();
        ledger.settleRefundByOutTradeNo(refundRow(no).getOutTradeNo(), proof.tradeNo(), proof.amountMinor());
        assertThat(refundRow(no).getStatus()).isEqualTo(StlPayment.SUCCESS);
        assertThat(refundRow(no).getRefundVerifiedMinor()).isEqualTo(1000);
    }

    @Test
    void concurrent_dispatchers_send_one_committed_intent() throws Exception {
        Fixture f = paidOrderWithBill("TEST");
        String no = settleService.prepareRefund(f.subOrderNo, "AS-" + f.subOrderNo, 1000, "refund");
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { start.await(); settleService.dispatchRefund(no); return true; });
            var second = threads.submit(() -> { start.await(); settleService.dispatchRefund(no); return true; });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        verify(testChannel, times(1)).refund(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void changed_intent_is_rejected_and_late_send_failure_cannot_undo_verified_success() {
        Fixture f = paidOrderWithBill("TEST");
        String as = "AS-" + f.subOrderNo;
        String no = settleService.prepareRefund(f.subOrderNo, as, 1000, "refund");
        assertThat(settleService.prepareRefund(f.subOrderNo, as, 1000, "refund")).isEqualTo(no);
        assertThatThrownBy(() -> settleService.prepareRefund(f.subOrderNo, as, 900, "refund"))
                .hasMessage("PAY_REFUND_INPUT_CONFLICT");
        ledger.markRefundSettled(no, "VERIFIED-" + no, 1000);
        ledger.markRefundSent(no, false, false, null, "late failure");
        assertThat(refundRow(no).getStatus()).isEqualTo(StlPayment.SUCCESS);
    }

    // ---------------------------------------------------------------- 夹具

    private record Fixture(String orderNo, String subOrderNo) {
    }

    /** 一笔已成功的收款 + 挂在同一子单上的结算单 —— 退款要两样都在才走得通 */
    private Fixture paidOrderWithBill(String payChannel) {
        int n = ++seq;
        String orderNo = "ODRC" + n + System.nanoTime() % 1_000_000;
        String subOrderNo = "SUBRC" + n + System.nanoTime() % 1_000_000;

        String out = ledger.open(new SettlePort.PaymentOpen(
                orderNo, "U-RC", null, payChannel, 20_000L, "测试商品"));
        String tradeNo = "TX-RC-" + n;
        if (ai.neargo.shop.pay.channel.TestPayGateway.CHANNEL.equals(payChannel)) {
            // 通道侧也要有这笔并且已付，否则退款会被通道拒（如实的行为）
            tradeNo = testChannel.placeOrder(out, 20_000L);
            testChannel.markPaid(out);
        }
        ledger.settle(new SettlePort.PaymentSettled(
                out, payChannel, tradeNo, 20_000L, System.currentTimeMillis()));

        StlBill b = new StlBill();
        b.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
        b.setSubOrderNo(subOrderNo);
        b.setOrderNo(orderNo);
        b.setEntityNo("M0001");
        b.setStoreNo("ST001");
        b.setPayMerchantNo("PM-TEST");
        b.setGrossMinor(20_000L);
        b.setCommissionMinor(1_000L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(19_000L);
        b.setCommissionRate(500);
        b.setFundsMode(ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_DIRECT);
        b.setSubsidyMinor(0L);
        b.setStatus(StlBill.PENDING);
        b.setRetryCount(0);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
        return new Fixture(orderNo, subOrderNo);
    }

    private StlPayment refundRow(String refundPaymentNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getPaymentNo, refundPaymentNo).last("LIMIT 1")));
    }
}
