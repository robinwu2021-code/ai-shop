package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>收款自查的处置</b>：pay 说通道那边是什么状况，这里决定订单该怎么走。
 *
 * <h2>为什么在这一层</h2>
 * 向通道查单是支付域的核心能力，而<b>把订单推回正轨是订单域的动作</b>。
 * 此前两件事都在 {@code ReconServiceImpl} 里，靠 {@code OrderRepairPort}
 * 反向调订单域 —— 而按「除回调外不做反向依赖」，那条依赖不该存在。
 *
 * <p>拆开之后：<b>对账的产出是「差异」，不是「修复」</b>。
 * pay 只回答「通道说这笔付了没」，处置在这里，与 I1–I3/I6 同一层。
 *
 * <h2>三种结果的处置刻意不同</h2>
 * <ul>
 *   <li><b>通道说已付</b> → 走原本的支付成功链路补回。
 *       不自己写一段「把 status 改成 SUCCESS」：那会漏掉发券、积分、通知、
 *       结算单里的某一个，而漏掉哪个要等用户来问才知道；</li>
 *   <li><b>通道根本没有这笔</b> → 我方发起失败，可以安全关单；</li>
 *   <li><b>查询本身失败</b> → <b>什么都不做</b>，留到下一轮。
 *       当成「通道没有这笔」去关单的话，一笔已付的单会被关掉 ——
 *       用户的钱在通道那边，而我方订单已关闭，只能退款并道歉。</li>
 * </ul>
 */
@Service
public class PaymentReconReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconReconciler.class);

    private final ReconService recon;
    private final OrderRepairPort orderRepair;

    public PaymentReconReconciler(ReconService recon, OrderRepairPort orderRepair) {
        this.recon = recon;
        this.orderRepair = orderRepair;
    }

    public Result scan(long now) {
        List<ReconService.Finding> findings = recon.checkStalePayments(now);
        int repaired = 0;
        int closed = 0;
        int deferred = 0;
        /*
         * **按渠道分解**（2026-09-01 加）。总数把渠道故障摊平了：
         * 微信查询全挂而支付宝正常时，运营看到的是「留待下轮 30」——
         * 与「三十笔正在回调路上」长得一模一样，<b>而处置完全相反</b>：
         * 前者要立刻去查那家的凭据与出口，后者什么都不用做。
         *
         * 用 TreeMap 而不是 HashMap：这些数字要打进日志与运营页面，
         * 顺序每轮都变的话，两轮之间没法直接比。
         */
        java.util.Map<String, int[]> byChannel = new java.util.TreeMap<>();

        for (ReconService.Finding f : findings) {
            // [scanned, repaired, closed, deferred]
            int[] c = byChannel.computeIfAbsent(
                    f.payChannel() == null ? "?" : f.payChannel(), k -> new int[4]);
            c[0]++;
            if (f.queryFailed()) {
                deferred++;
                c[3]++;
                continue;
            }
            if (f.paidOnChannel()) {
                String note;
                try {
                    orderRepair.markPaid(f.orderNo(), f.payChannel(), f.channelTradeNo());
                    repaired++;
                    c[1]++;
                    note = "自查发现通道已支付，已补回支付成功链路（通道单号 "
                            + f.channelTradeNo() + "）";
                } catch (RuntimeException e) {
                    /*
                     * 补回失败**不能让整轮扫描炸掉**：一笔补不回来，后面几百笔就都不查了。
                     * 而且这种单恰恰**最需要被记下来** —— 通道收了钱，
                     * 而我方连订单都推不动（订单不存在、或已经被关掉）。
                     * 这是要人去处理的，不是重试能解决的。
                     */
                    deferred++;
                    c[3]++;
                    note = "通道已支付但补回失败（" + e.getMessage() + "）—— 通道单号 "
                            + f.channelTradeNo() + "，需人工核对订单 " + f.orderNo();
                    log.warn("[recon] 补回失败 payment={} order={}：{}",
                            f.paymentNo(), f.orderNo(), e.toString());
                }
                recon.recordFinding(f, StlReconDiff.PLATFORM_ONLY, note);

                if (f.channelAmountMinor() > 0 && f.ourAmountMinor() != null
                        && f.channelAmountMinor() != f.ourAmountMinor()) {
                    // 金额不符要单独记一条：补回支付不代表账对上了
                    recon.recordFinding(f, StlReconDiff.AMOUNT_DIFF,
                            "通道 " + f.channelAmountMinor() + " 与我方 "
                                    + f.ourAmountMinor() + " 不符");
                }
            } else if (f.notFound()) {
                orderRepair.closeUnpaid(f.orderNo());
                closed++;
                c[2]++;
            } else {
                // 通道有这笔但没付：正常的用户放弃，交给关单任务，不算差异
                deferred++;
                c[3]++;
            }
        }
        List<ChannelSlice> slices = byChannel.entrySet().stream()
                .map(e -> new ChannelSlice(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], e.getValue()[3]))
                .toList();
        log.info("[recon] 自查 {} 笔：补回 {} · 关单 {} · 留待下轮 {}",
                findings.size(), repaired, closed, deferred);
        for (ChannelSlice sl : slices) {
            if (sl.allDeferred()) {
                /*
                 * **这一条是加按渠道分解的全部理由。**
                 * 一个渠道扫到的每一笔都判不了 —— 这不是「有几笔在路上」，
                 * 这是这家通道我方查不通（凭据过期、出口 IP 变了、对方在维护）。
                 * 它在总数里看不出来，而它要人立刻去看。
                 */
                log.warn("[recon] **{} 这一轮 {} 笔全部判不了** —— "
                                + "不是几笔在路上，是这家通道查不通：查凭据、出口 IP、对方公告",
                        sl.payChannel(), sl.scanned());
            }
        }
        return new Result(findings.size(), repaired, closed, deferred, slices);
    }

    /**
     * @param scanned <b>对照量</b>：0 的时候后面三个数一个都不说明问题。
     *                而这个数长期为 0 曾经是个真实的坑 —— stl_payment 那张表
     *                在 2026-09-01 之前根本没人写，这条轴一直在对空表
     * @param byChannel 按渠道的分解。见 {@link ChannelSlice}
     */
    public record Result(int scanned, int repaired, int closed, int deferred,
                         List<ChannelSlice> byChannel) {
    }

    /**
     * 一个渠道这一轮的分解。
     *
     * <p>结算侧本来就按渠道分批（{@code stl_settle_batch.pay_channel}），
     * 而对账侧此前只有总数 —— 两边口径对不上，
     * 运营拿着「今天留待 30 笔」没法回答「哪一批不能放款」。
     */
    public record ChannelSlice(String payChannel, int scanned, int repaired,
                               int closed, int deferred) {

        /**
         * 这个渠道扫到的每一笔都判不了。
         *
         * <p><b>scanned &gt; 0 是判据的一半</b>：没扫到单的渠道
         * 「全部判不了」恒为真，而那句话毫无意义 —— 会天天报一次假警。
         */
        public boolean allDeferred() {
            return scanned > 0 && deferred == scanned;
        }
    }
}
