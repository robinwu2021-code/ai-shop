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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>退款要真的发到通道</b>（2026-09-04）。
 *
 * <h2>此前它只落账</h2>
 * {@code SettleServiceImpl.refund} 里写着「还没有接通道退款 —— 那要等真通道凭证」：
 * 落一行 {@code REFUND} 流水就返回了。凭证到位之后，不接的话就是
 * <b>最不对称的那种缺口</b>：钱能收进来、退不出去，
 * 而我方账上、订单上、售后单上都写着已退款，只有用户的银行卡知道没有。
 *
 * <h2>判据是「通道退款单号有没有落到行上」</h2>
 * 断言「退款流水存在」证明不了任何东西 —— 那在接通道之前就是绿的。
 * 只有通道回执里的单号（{@code TESTRF-...}）出现在行上，
 * 才说明这一步真的走出去过。<b>撤掉 {@code sendRefundToChannel} 必须变红。</b>
 *
 * <p>用 {@code TEST} 通道而不是 {@code STUB}：前者记状态、能被回查，
 * 后者恒成功且什么都不记 —— 拿它做判据等于什么都没验（见 {@code StubPayGateway} 类注释）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundReachesChannelTest {

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
    @Autowired
    private ai.neargo.shop.pay.channel.TestPayGateway testChannel;

    private static int seq = 0;

    @Test
    @DisplayName("★★★ 退款发到通道，通道退款单号落在流水上 —— 只落账不发钱是最不对称的缺口")
    void refundIsSentToTheChannel() {
        Fixture f = paidOrderWithBill("TEST");

        String refundNo = settleService.refund(f.subOrderNo, 5_000L, "七天无理由");

        assertThat(refundNo).isNotBlank();
        StlPayment row = refundRow(refundNo);
        assertThat(row.getTradeNo())
                .as("通道退款单号是空的 —— 这笔退款根本没发出去，而账上已经写着退了")
                .startsWith("TESTRF-");
    }

    @Test
    @DisplayName("★★★ 通道受理后仍是 PENDING —— 微信退款异步，PROCESSING 是常态")
    void acceptedRefundStaysPending() {
        Fixture f = paidOrderWithBill("TEST");

        String refundNo = settleService.refund(f.subOrderNo, 1_000L, "少发");

        assertThat(refundRow(refundNo).getStatus())
                .as("受理就写 SUCCESS 的话，账上写着退了而钱还在路上；"
                        + "通道最终拒单时这个差异只有用户投诉才会被发现")
                .isEqualTo(StlPayment.PENDING);
    }

    @Test
    @DisplayName("★★★ 通道没接入时转 FAILED —— 留 PENDING 会被对账轴当成「通道没这笔」而关掉")
    void unreachableChannelFailsLoudly() {
        // 这个通道名没有任何网关认领，router 会直接抛
        Fixture f = paidOrderWithBill("NO-SUCH-CHANNEL");

        String refundNo = settleService.refund(f.subOrderNo, 2_000L, "试");

        StlPayment row = refundRow(refundNo);
        assertThat(row.getStatus())
                .as("留 PENDING 的话，对账轴回查会得到「通道没有这笔」——"
                        + "而那正是它用来安全关单的判据，一笔该退的钱会被静默关掉")
                .isEqualTo(StlPayment.FAILED);
        assertThat(row.getErrMsg()).contains("通道未接入");
    }

    @Test
    @DisplayName("★★ 流水仍然要落下 —— 发通道失败不能把这一行一起回滚掉")
    void ledgerRowSurvivesChannelFailure() {
        Fixture f = paidOrderWithBill("NO-SUCH-CHANNEL");

        String refundNo = settleService.refund(f.subOrderNo, 2_000L, "试");

        assertThat(refundNo)
                .as("回滚掉的话，一笔可能已经发到通道的退款在我方一点痕迹都没有")
                .isNotBlank();
        assertThat(refundRow(refundNo)).isNotNull();
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
                orderNo, "U-RC", null, payChannel, 20_000L));
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
