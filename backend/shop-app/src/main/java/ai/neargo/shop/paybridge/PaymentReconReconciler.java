package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import java.time.Duration;
import java.time.Instant;
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
 * <h2>退款行走另一条路</h2>
 * 这条轴同时扫 {@code PAY} 与 {@code REFUND}，而下面这三条是<b>为收款写的</b>。
 * 退款行按 {@code Finding#isRefund} 提前分叉 —— 混在一起处置正好做反，
 * 详见 {@code ReconService.Finding#isRefund} 的注释。
 *
 * <h2>三种结果的处置刻意不同（**收款**）</h2>
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
    /** 退款行的终态写在支付域自己的账上，不经订单域 */
    private final ai.neargo.shop.pay.service.PaymentLedgerService paymentLedger;

    /**
     * 「这个渠道全部判不了」持续期间，隔多久重述一次。
     *
     * <p>这条轴约 8~9 分钟一轮（实测 24 小时 142 轮）。渠道查不通往往一连几天都不好，
     * 而此前每轮都原样打一条 WARN —— <b>一天约 167 条一模一样的告警</b>，
     * 占了 WARN 通道的一半以上。节奏与理由见 {@link StuckStateLog}。
     */
    private static final Duration RESTATE_EVERY = Duration.ofHours(1);

    /** key = payChannel。见 {@link StuckStateLog} 的类注释。 */
    private final StuckStateLog stuck = new StuckStateLog(RESTATE_EVERY);

    public PaymentReconReconciler(ReconService recon, OrderRepairPort orderRepair,
                                  ai.neargo.shop.pay.service.PaymentLedgerService paymentLedger) {
        this.recon = recon;
        this.orderRepair = orderRepair;
        this.paymentLedger = paymentLedger;
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
            /*
             * **退款行不能走下面那三条路。**这条轴从 2026-09-02 起同时扫
             * PAY 与 REFUND，而下面整段是为**收款**写的（见类注释）——
             * 对退款行来说 paidOnChannel 的含义是「**退款**成功了」，
             * 拿它去 markPaid 会把一笔已退款的订单改回已支付；
             * 而「通道没有这笔退款」会去 closeUnpaid，把一笔已付的订单关掉。
             *
             * 2026-09-04 之前走不到：退款从没真的发给过通道，queryRefund
             * 永远查不到。退款接上通道的那一刻它就在线了。
             */
            if (f.isRefund()) {
                if (f.paidOnChannel()) {
                    // 钱确实退出去了 —— 把退款流水推到终态。这是这条轴唯一该对退款做的事
                    try {
                        paymentLedger.markRefundSettled(f.paymentNo(), f.channelTradeNo());
                        repaired++;
                        c[1]++;
                    } catch (RuntimeException e) {
                        /*
                         * **一笔推不动不能让整轮炸掉** —— 与上面收款那条同一个道理：
                         * 后面几百笔就都不查了。这里更要紧，因为退款这一侧
                         * 「钱可能已经出去了」，而扫描中断的表现是**什么都没发生**。
                         */
                        deferred++;
                        c[3]++;
                        log.warn("[recon] 退款确认失败 payment={}：{}", f.paymentNo(), e.toString());
                    }
                } else if (f.notFound()) {
                    /*
                     * 通道那边没有这笔退款 —— 我方以为发出去了，其实没有。
                     * **绝不能碰订单**：这是退款没发成，不是订单没付成。
                     * 记差异转人工：钱还在我方这边，而用户在等退款。
                     */
                    recon.recordFinding(f, StlReconDiff.PLATFORM_ONLY,
                            "退款单 " + f.outTradeNo() + " 通道那边不存在 —— "
                                    + "我方以为已发起，实际没发出去，用户在等这笔钱");
                    deferred++;
                    c[3]++;
                } else {
                    // 通道有这笔但还没退完（PROCESSING）—— 正常的中间态，下一轮再看
                    deferred++;
                    c[3]++;
                }
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
        reportStuckChannels(slices, Instant.now());
        return new Result(findings.size(), repaired, closed, deferred, slices);
    }

    /**
     * 报「这个渠道扫到的每一笔都判不了」——<b>按状态变化报，不是按轮次报</b>。
     *
     * <h2>为什么不每轮都打</h2>
     * 一个渠道查不通往往一连几天都不好，而这条轴 8~9 分钟一轮：每轮原样打一条，
     * 一天就是约 167 条一模一样的 WARN，把 WARN 通道占掉一半以上。
     * 那条告警本身写着「它要人立刻去看」—— <b>重复 167 次恰恰毁掉了这个性质</b>：
     * 看的人分不出这是新出的还是坏了好几天。
     *
     * <h2>改成什么</h2>
     * <ul>
     *   <li><b>刚开始</b>（这一轮才进入这个状态）→ 照打完整 WARN，与此前一样，一刻不推迟；</li>
     *   <li><b>持续中</b> → 每 {@link #RESTATE_EVERY} 重述一次，且<b>带上持续了多久、多少轮</b>
     *       —— 一条「持续 167 轮、自 09-15 09:40 起」比 167 条一模一样的更有信息量；</li>
     *   <li><b>恢复了</b> → 打一条 INFO。<b>此前根本没有这一条</b>：通道好了没人知道，
     *       只能靠「WARN 不再出现」去反推，而那与「任务挂了、压根没跑」长得一模一样。</li>
     * </ul>
     *
     * <p><b>刻意不做的：不把它降级、不把它静音。</b> 这条 WARN 是真问题的信号
     * （写这段时线上 WECHAT 已连续多轮 21 笔查不通）。这里改的只是「同一件事说几遍」，
     * 不是「还说不说」—— 修日志噪音时把问题一起消音，是比噪音更坏的结果。
     *
     * @param slices 这一轮按渠道的分解
     * @param now    传进来而不是内部取，便于测试推进时间
     */
    void reportStuckChannels(List<ChannelSlice> slices, Instant now) {
        for (ChannelSlice sl : slices) {
            String ch = sl.payChannel();
            if (sl.allDeferred()) {
                StuckStateLog.State st = stuck.stillBad(ch, now);
                if (st == null) {
                    continue;   // 还在重述间隔内：这一轮不说话
                }
                if (st.first()) {
                    log.warn("[recon] **{} 这一轮 {} 笔全部判不了** —— "
                                    + "不是几笔在路上，是这家通道查不通：查凭据、出口 IP、对方公告",
                            ch, sl.scanned());
                } else {
                    log.warn("[recon] **{} 仍然全部判不了**：已持续 {} 轮 / {} 分钟"
                                    + "（本轮 {} 笔，自 {} 起）—— 查凭据、出口 IP、对方公告",
                            ch, st.rounds(), st.minutes(), sl.scanned(), st.since());
                }
            } else {
                StuckStateLog.State ok = stuck.recovered(ch, now);
                if (ok != null) {
                    log.info("[recon] {} 恢复了：判得动了（此前连续 {} 轮 / {} 分钟全部判不了，自 {} 起）",
                            ch, ok.rounds(), ok.minutes(), ok.since());
                }
            }
        }
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
