package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.support.port.FakePayQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账自查（[对账差异-方案] 第一步）。
 *
 * <p><b>这组用例守的是「什么时候可以关单」</b>。三种通道回答对应三种走向，
 * 而搞错其中一种的代价完全不对称：
 * <ul>
 *   <li>把「已支付」当成没付 → 用户付了钱、单被关掉，只能退款道歉</li>
 *   <li>把「查询失败」当成「通道没这笔」→ 同上，而且是系统性的（通道抖动时成批发生）</li>
 *   <li>把「没付」当成已付 → 给一笔没收到钱的单发货</li>
 * </ul>
 * 所以每一条都单独测，不合并。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconFlowTest {

    @Autowired
    /** 处置（补支付/关单）搬到 paybridge（2026-09-01）—— pay 只核对与记差异 */
    private ai.neargo.shop.paybridge.PaymentReconReconciler paymentRecon;

    @Autowired
    /** 裁决与覆盖范围仍在 pay：差异是 pay 自己的账 */
    private ai.neargo.shop.pay.service.ReconService reconService;

    @Autowired
    private FakePayQueryPort fakeQuery;

    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper paymentMapper;

    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.ReconDiffMapper diffMapper;

    private static int seq = 0;

    @BeforeEach
    void reset() {
        fakeQuery.reset();
    }

    /** 造一笔 25 分钟前发起、至今停在 PENDING 的收款 */
    private StlPayment stalePayment() {
        return stalePayment("WECHAT");
    }

    private StlPayment stalePayment(String channel) {
        StlPayment p = new StlPayment();
        p.setPaymentNo("PY-RECON-" + (++seq));
        p.setDirection(StlPayment.PAY);
        p.setStatus(StlPayment.PENDING);
        p.setOrderNo("OD-RECON-" + seq);
        p.setOutTradeNo("OUT-RECON-" + seq);
        p.setPayChannel(channel);
        p.setUserNo("U-RECON");
        p.setAmountMinor(9900L);
        p.setCreatedAt(LocalDateTime.now().minusMinutes(25));
        paymentMapper.insert(p);
        return p;
    }

    private List<StlReconDiff> diffsOf(String paymentNo) {
        return diffMapper.selectList(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getPaymentNo, paymentNo));
    }

    @Test
    @DisplayName("★★ 查询失败绝不关单 —— 通道抖一下就把已付的单成批关掉，是资金事故")
    void queryFailureNeverCloses() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(false, false, false, 0, null));

        var r = paymentRecon.scan(System.currentTimeMillis());

        assertThat(fakeQuery.asked()).contains(p.getOutTradeNo());
        assertThat(r.closed()).isZero();
        assertThat(r.deferred()).isPositive();
        // 单还在原地等下一轮，且不落差异 —— 查不通不是差异，是我们没查到
        assertThat(paymentMapper.selectById(p.getId()).getStatus()).isEqualTo(StlPayment.PENDING);
        assertThat(diffsOf(p.getPaymentNo())).isEmpty();
    }

    @Test
    @DisplayName("★ 通道说没有这笔 → 可以安全关单")
    void notFoundClosesOrder() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, false, false, 0, null));

        var r = paymentRecon.scan(System.currentTimeMillis());

        assertThat(r.closed()).isPositive();
        assertThat(fakeQuery.asked()).contains(p.getOutTradeNo());
    }

    @Test
    @DisplayName("★ 通道有这笔但没付 → 交给关单任务，不算差异")
    void unpaidIsNotADiff() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, false, true, 0, null));

        var r = paymentRecon.scan(System.currentTimeMillis());

        assertThat(r.closed()).isZero();
        assertThat(diffsOf(p.getPaymentNo())).isEmpty();
    }

    @Test
    @DisplayName("★★ 通道说已付 → 落一条差异，且金额不符时再落一条")
    void paidRecordsDiff() {
        StlPayment p = stalePayment();
        // 通道说 100.00，我方记的是 99.00 —— 两条差异：掉单 + 金额不符
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 10000L, "WX-TX-1"));

        paymentRecon.scan(System.currentTimeMillis());

        assertThat(diffsOf(p.getPaymentNo()))
                .extracting(StlReconDiff::getDiffType)
                .contains(StlReconDiff.PLATFORM_ONLY, StlReconDiff.AMOUNT_DIFF);
        // 差异要能追回通道流水号，否则运营拿什么去通道后台核对
        assertThat(diffsOf(p.getPaymentNo()))
                .allSatisfy(d -> assertThat(d.getChannelTxnNo()).isEqualTo("WX-TX-1"));
    }

    @Test
    @DisplayName("★ 同一笔在同一账期只落一条 —— 每 10 分钟跑一轮，不去重会堆成几十条")
    void diffIsDedupedPerDay() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 9900L, "WX-TX-2"));

        paymentRecon.scan(System.currentTimeMillis());
        paymentRecon.scan(System.currentTimeMillis());
        paymentRecon.scan(System.currentTimeMillis());

        assertThat(diffsOf(p.getPaymentNo())).hasSize(1);
    }

    @Test
    @DisplayName("★★ 裁决必须写结论 —— 没有结论的「已处理」等于没处理")
    void decideRequiresResolution() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 9900L, "WX-TX-3"));
        paymentRecon.scan(System.currentTimeMillis());
        String diffNo = diffsOf(p.getPaymentNo()).getFirst().getDiffNo();

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ai.neargo.shop.common.BizException.class,
                () -> reconService.decide(diffNo, true, "  ", "OPS1")))
                .isNotNull();

        var vo = reconService.decide(diffNo, true, "通道后台核对后确认是重复通知", "OPS1");
        assertThat(vo.status()).isEqualTo(StlReconDiff.IGNORED);

        // 裁完是终态：再裁一次意味着同一条差异有两个结论
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ai.neargo.shop.common.BizException.class,
                () -> reconService.decide(diffNo, false, "改主意了", "OPS1")))
                .isNotNull();
    }

    @Test
    @DisplayName("★★ 覆盖范围必须说「渠道账单没接」—— 空列表不等于账是平的")
    void coverageTellsTheTruth() {
        var c = reconService.coverage();
        assertThat(c.channelBillConnected()).isFalse();
        // 这句话直接显示给运营，不能是空的
        assertThat(c.note()).contains("渠道账单未接入");
    }

    @Test
    @DisplayName("★★★ 一家通道查不通、另一家正常 —— 总数里看不出来，而处置完全相反")
    void oneBlindChannelIsCallableOut() {
        StlPayment wx = stalePayment("WECHAT");
        StlPayment ali = stalePayment("ALIPAY");
        // 微信查不通（凭据过期 / 出口 IP 变了 / 对方维护）；支付宝说「没有这笔」，可以安全关单
        fakeQuery.answerFor("WECHAT", new PayQueryPort.Result(false, false, false, 0, null));
        fakeQuery.answerFor("ALIPAY", new PayQueryPort.Result(true, false, false, 0, null));

        var r = paymentRecon.scan(System.currentTimeMillis());

        var wxSlice = r.byChannel().stream()
                .filter(s -> s.payChannel().equals("WECHAT")).findFirst().orElseThrow();
        var aliSlice = r.byChannel().stream()
                .filter(s -> s.payChannel().equals("ALIPAY")).findFirst().orElseThrow();

        // 这一条是加按渠道分解的全部理由：微信整个判不了，支付宝没事
        assertThat(wxSlice.allDeferred())
                .as("微信这一轮每一笔都判不了，却报不出来 —— 那就是「几笔在路上」与"
                        + "「这家通道查不通」分不开，而后者要人立刻去看")
                .isTrue();
        assertThat(aliSlice.allDeferred()).isFalse();
        assertThat(aliSlice.closed()).isPositive();

        // 总数仍然对得上分解 —— 对不上的话两个口径会各说各话
        assertThat(r.byChannel()).extracting(
                        ai.neargo.shop.paybridge.PaymentReconReconciler.ChannelSlice::scanned)
                .isNotEmpty();
        assertThat(r.byChannel().stream().mapToInt(
                ai.neargo.shop.paybridge.PaymentReconReconciler.ChannelSlice::scanned).sum())
                .isEqualTo(r.scanned());
        assertThat(r.byChannel().stream().mapToInt(
                ai.neargo.shop.paybridge.PaymentReconReconciler.ChannelSlice::deferred).sum())
                .isEqualTo(r.deferred());

        assertThat(wx.getPaymentNo()).isNotEqualTo(ali.getPaymentNo());
    }

    @Test
    @DisplayName("★★ 没扫到单的渠道不算「全判不了」—— 否则每天报一次假警")
    void emptyChannelIsNotBlind() {
        var empty = new ai.neargo.shop.paybridge.PaymentReconReconciler
                .ChannelSlice("WECHAT", 0, 0, 0, 0);

        assertThat(empty.allDeferred()).isFalse();
    }
}
