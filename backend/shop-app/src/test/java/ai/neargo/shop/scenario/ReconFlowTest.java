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
    private ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper orderMapper;

    /** 本类造的订单：**共享种子，跑完要清** —— 不清的话后面的用例会读到它 */
    private final java.util.List<String> createdOrders = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void dropCreatedOrders() {
        for (String no : createdOrders) {
            orderMapper.delete(Wrappers.<ai.neargo.shop.trade.entity.OrdOrder>lambdaQuery()
                    .eq(ai.neargo.shop.trade.entity.OrdOrder::getOrderNo, no));
        }
        createdOrders.clear();
    }

    /** 造一张指定状态的订单，用来模拟「差异记下之后，订单那边后来变成了什么」。 */
    private void seedOrder(String orderNo, String status) {
        var o = new ai.neargo.shop.trade.entity.OrdOrder();
        o.setOrderNo(orderNo);
        o.setUserNo("U-RECON");
        o.setStatus(status);
        o.setPayAmount(9900L);
        o.setCreatedAt(LocalDateTime.now());
        orderMapper.insert(o);
        createdOrders.add(orderNo);
    }

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

    // ───────────────────────── 退款也进对账（S8 · 2026-09-02）

    /** 造一笔 25 分钟前发起、至今停在 PENDING 的**退款** */
    private StlPayment stalRefund() {
        StlPayment p = new StlPayment();
        p.setPaymentNo("RF-RECON-" + (++seq));
        p.setDirection(StlPayment.REFUND);
        p.setStatus(StlPayment.PENDING);
        p.setOrderNo("OD-RFRECON-" + seq);
        p.setSubOrderNo("SUB-RFRECON-" + seq);
        p.setAfterSaleNo("AS-RFRECON-" + seq);
        p.setOutTradeNo("OUT-RFRECON-" + seq + "-R1");
        p.setPayChannel("WECHAT");
        p.setUserNo("U-RECON");
        p.setAmountMinor(3300L);
        p.setCreatedAt(LocalDateTime.now().minusMinutes(25));
        paymentMapper.insert(p);
        return p;
    }

    @Test
    @DisplayName("★★★ 停在 PENDING 的退款也要被扫到 —— 钱可能已经出去而我方不知道")
    void staleRefundIsScanned() {
        StlPayment r = stalRefund();
        fakeQuery.answer(new PayQueryPort.Result(false, false, false, 0, null));

        paymentRecon.scan(System.currentTimeMillis());

        assertThat(fakeQuery.askedRefund())
                .as("退款没有被扫到 —— 而一笔停在 PENDING 的退款意味着"
                        + "「钱可能已经退出去而我方不知道」，与掉单同样严重，方向相反")
                .contains(r.getOutTradeNo());
    }

    @Test
    @DisplayName("★★★ 退款要问退款接口，不能问收款接口 —— 问错了会把待确认的退款批量关掉")
    void refundGoesToRefundQuery() {
        StlPayment r = stalRefund();
        fakeQuery.answer(new PayQueryPort.Result(true, false, false, 0, null));

        paymentRecon.scan(System.currentTimeMillis());

        assertThat(fakeQuery.askedRefund())
                .as("退款单号应当进退款查询").contains(r.getOutTradeNo());
        assertThat(fakeQuery.asked())
                .as("退款单号跑到收款查询去了 —— 通道那边收款单里没有这个号，"
                        + "会回「没有这笔」，而对账把它当成可以安全关单。"
                        + "于是待确认的退款被批量关掉，**而钱可能真的已经退出去了**")
                .doesNotContain(r.getOutTradeNo());
    }

    // ───────────────── 退款的**处置**（2026-09-04）

    /*
     * 上面两条只断言「问对了接口」，回的都是 paid=false ——
     * **退款成功那条分支从来没被走过**。而处置那一层整段是为收款写的：
     * 对退款行来说 paidOnChannel 的含义是「退款成功」，
     * 拿它去 markPaid 会把一笔已退款的订单改回已支付。
     *
     * 这条在退款真的发给通道之前走不到（queryRefund 永远查不到），
     * 所以它一直静静地绿着。
     */

    @Test
    @DisplayName("★★★ 退款查到已退成功 → 转 SUCCESS，**绝不能去补回订单的支付成功链路**")
    void refundPaidSettlesTheRefundNotTheOrder() {
        StlPayment r = stalRefund();
        // 对退款单来说，paid=true 的含义是「**退款**成功了」
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 3300L, "WXRF-1"));

        paymentRecon.scan(System.currentTimeMillis());

        assertThat(reload(r).getStatus())
                .as("退款流水没被推到终态 —— 那它会永远停在 PENDING，"
                        + "对账每一轮都捞出来、每一轮都问通道、每一轮什么都不做")
                .isEqualTo(StlPayment.SUCCESS);
        assertThat(reload(r).getSucceededAt()).isNotNull();
        /*
         * **断言只看自己这一行**，不用 scan 的计数器：那是全局的，
         * 而这个类里前面的用例会留下 PENDING 行，同一次 scan 会一起处理掉。
         */
        assertThat(diffsOf(r))
                .as("退款成功走进了收款那条路 —— 那会 markPaid 把一笔已退款的订单改回已支付")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 通道没有这笔**退款**时绝不动订单 —— 那是退款没发成，不是订单没付成")
    void refundNotFoundMustNotTouchTheOrder() {
        StlPayment r = stalRefund();
        // ok=true, paid=false, found=false —— 通道说「没有这笔退款」
        fakeQuery.answer(new PayQueryPort.Result(true, false, false, 0, null));

        paymentRecon.scan(System.currentTimeMillis());

        // 走对分支的证据：记了一条「退款单通道那边不存在」的差异，而不是去关订单
        assertThat(diffsOf(r))
                .as("没记差异 —— 说明这一行走的是收款那条路（关订单），"
                        + "而那会关掉一笔用户已经付过钱的订单")
                .isNotEmpty();
        assertThat(diffsOf(r).getFirst().getResolution())
                .contains("通道那边不存在");
        assertThat(reload(r).getStatus())
                .as("通道没有这笔退款时不能改退款流水的状态 —— 钱还在我方这边，要人工看")
                .isEqualTo(StlPayment.PENDING);
    }

    @Test
    @DisplayName("★★ 退款确认是幂等的 —— 重复扫不改已有的成功时刻")
    void refundSettleIsIdempotent() {
        StlPayment r = stalRefund();
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 3300L, "WXRF-2"));

        paymentRecon.scan(System.currentTimeMillis());
        Long first = reload(r).getSucceededAt();
        paymentRecon.scan(System.currentTimeMillis());

        assertThat(reload(r).getSucceededAt())
                .as("成功时刻被改写了 —— 对账查到的时刻会一轮一轮往后跳")
                .isEqualTo(first);
    }

    /** 这一笔流水记下的差异。**按 paymentNo 取**，不受同类其他用例遗留行的影响 */
    private List<StlReconDiff> diffsOf(StlPayment p) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(
                () -> diffMapper.selectList(Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getPaymentNo, p.getPaymentNo())));
    }

    private StlPayment reload(StlPayment p) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(
                () -> paymentMapper.selectOne(Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getPaymentNo, p.getPaymentNo()).last("LIMIT 1")));
    }

    /**
     * 差异表此前**只进不出**。2026-09-28 线上那条「通道已支付但补回失败」，
     * 实际问题当天 14:02 就好了（订单已 PAID），而差异行到第二天还挂着 PENDING ——
     * 运营翻到它会去查一个早就没事的单，真差异混在里面更难被发现。
     */
    @Test
    @DisplayName("★★★ 差异后来自己好了 → 复查时自动收口，不再挂着占地方")
    void healedDiffIsSettled() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 9900L, "WX-HEAL-1"));
        paymentRecon.scan(System.currentTimeMillis());
        assertThat(diffsOf(p.getPaymentNo())).isNotEmpty();

        // 订单那边后来好了（回调到了 / 人工补的）
        seedOrder(p.getOrderNo(), ai.neargo.shop.trade.entity.OrdOrder.PAID);

        int[] settled = paymentRecon.settlePendingDiffs();

        assertThat(settled[0]).as("一条都没收口 —— 差异表还是只进不出").isGreaterThanOrEqualTo(1);
        assertThat(diffsOf(p.getPaymentNo()))
                .filteredOn(d -> StlReconDiff.PLATFORM_ONLY.equals(d.getDiffType()))
                .allSatisfy(d -> {
                    assertThat(d.getStatus()).isEqualTo(StlReconDiff.RESOLVED);
                    assertThat(d.getResolvedBy()).as("要能一眼看出是系统收的，不是某个真人").isEqualTo("SYSTEM");
                });
    }

    @Test
    @DisplayName("★★★ 还没好的差异**留着** —— 自动收口不能把真问题一起抹掉")
    void unhealedDiffStaysPending() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 9900L, "WX-HEAL-2"));
        paymentRecon.scan(System.currentTimeMillis());

        // 订单被关了：markPaid 会被状态机拒（CANCELLED → PAID 不合法）
        seedOrder(p.getOrderNo(), ai.neargo.shop.trade.entity.OrdOrder.CANCELLED);
        String before = diffsOf(p.getPaymentNo()).getFirst().getResolution();

        int[] settled = paymentRecon.settlePendingDiffs();

        assertThat(settled[1]).as("未解决数为 0 —— 那这个计数没在数真东西").isGreaterThanOrEqualTo(1);
        assertThat(diffsOf(p.getPaymentNo()))
                .filteredOn(d -> StlReconDiff.PLATFORM_ONLY.equals(d.getDiffType()))
                .allSatisfy(d -> {
                    assertThat(d.getStatus()).isEqualTo(StlReconDiff.PENDING);
                    assertThat(d.getResolution())
                            .as("每轮覆盖 resolution 的话，「从哪天起一直补不回来」就没了")
                            .isEqualTo(before);
                });
    }
}
