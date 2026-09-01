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

        for (ReconService.Finding f : findings) {
            if (f.queryFailed()) {
                deferred++;
                continue;
            }
            if (f.paidOnChannel()) {
                String note;
                try {
                    orderRepair.markPaid(f.orderNo(), f.payChannel(), f.channelTradeNo());
                    repaired++;
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
            } else {
                // 通道有这笔但没付：正常的用户放弃，交给关单任务，不算差异
                deferred++;
            }
        }
        log.info("[recon] 自查 {} 笔：补回 {} · 关单 {} · 留待下轮 {}",
                findings.size(), repaired, closed, deferred);
        return new Result(findings.size(), repaired, closed, deferred);
    }

    /**
     * @param scanned <b>对照量</b>：0 的时候后面三个数一个都不说明问题。
     *                而这个数长期为 0 曾经是个真实的坑 —— stl_payment 那张表
     *                在 2026-09-01 之前根本没人写，这条轴一直在对空表
     */
    public record Result(int scanned, int repaired, int closed, int deferred) {
    }
}
