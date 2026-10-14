package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.LedgerResult;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundOutcome;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundableView;
import ai.neargo.shop.trade.order.refund.policy.ApprovedAmountAllocator;
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;

import java.util.List;

/**
 * 现金退款的执行（取代 {@code AfterSaleServiceImpl#doRefund} 的现金分支）。顺序：
 * <ol>
 *   <li>记账（条件增量，R2）—— 并发超退在这里就 CONFLICT，钱还没动</li>
 *   <li>按额回退分账（{@code REV-售后单号}）—— 失败抛 SPLIT_EXPIRED，整笔回滚，<b>绝不往下退钱</b></li>
 *   <li>原路退款（幂等键 = 售后单号）</li>
 * </ol>
 * 回补库存、改状态、退券由调用方按 {@link RefundOutcome} 做。每一步可重放（R3）。
 */
public class AfterSaleRefundExecutor {

    private final RefundableQuery query;
    private final RefundPlanner planner;
    private final ApprovedAmountAllocator allocator;
    private final RefundLedger ledger;
    private final AfterSaleLineStore lines;
    private final SettlePort settle;

    public AfterSaleRefundExecutor(RefundableQuery query, RefundPlanner planner, ApprovedAmountAllocator allocator,
                                   RefundLedger ledger, AfterSaleLineStore lines, SettlePort settle) {
        this.query = query;
        this.planner = planner;
        this.allocator = allocator;
        this.ledger = ledger;
        this.lines = lines;
        this.settle = settle;
    }

    public RefundOutcome execute(OrdAfterSale as, OrdSubOrder sub) {
        RefundableView view = query.view(sub);
        RefundPlan plan = finalPlan(as, view);
        if (plan.isPartial(view) && !view.partialSupported()) {
            throw BizException.of(ErrorCode.AFTERSALE_CHANNEL_PARTIAL_UNSUPPORTED);
        }
        LedgerResult result = ledger.record(plan);
        SplitReversal reversal = new SplitReversal(sub.getSubOrderNo(), as.getAfterSaleNo(), plan.goodsMinor(),
                plan.freightMinor(), LineShares.goodsPaid(sub));
        if (!settle.reverseSplit(reversal)) {
            throw BizException.of(ErrorCode.SPLIT_EXPIRED);
        }
        String paymentNo = settle.refund(sub.getSubOrderNo(), as.getAfterSaleNo(), plan.totalMinor(), as.getReason());
        return new RefundOutcome(plan, result, paymentNo);
    }

    /**
     * 申请时存下的行 + 运费（= 申请额 − 行合计），再按商家核定额分回。
     * 5.5 之前建的售后单没有行：按整单剩余（含运费）出方案，再分回到它的申请 / 核定额，并把行补存下来。
     */
    RefundPlan finalPlan(OrdAfterSale as, RefundableView view) {
        List<RefundLine> saved = lines.lines(as.getAfterSaleNo());
        long applied = as.getRefundMinor() == null ? 0L : as.getRefundMinor();
        RefundPlan requested = saved.isEmpty()
                ? planner.plan(view, null, false, null)
                : new RefundPlan(as.getSubOrderNo(), saved,
                        Math.max(0L, applied - saved.stream().mapToLong(RefundLine::amountMinor).sum()));
        long target = as.getApprovedMinor() != null ? as.getApprovedMinor() : applied;
        RefundPlan plan = allocator.allocate(requested, target);
        if (saved.isEmpty()) {
            lines.saveLines(as.getAfterSaleNo(), as.getSubOrderNo(), plan.lines());
        } else {
            lines.updateAmounts(as.getAfterSaleNo(), plan.lines());
        }
        return plan;
    }
}
