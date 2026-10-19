package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundableView;
import ai.neargo.shop.trade.order.refund.policy.ApprovedAmountAllocator;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;

import java.util.List;

/** Prepares an immutable source plan; cash sending and success finalization run in separate stages. */
public class AfterSaleRefundExecutor {

    private final RefundableQuery query;
    private final RefundPlanner planner;
    private final ApprovedAmountAllocator allocator;
    private final RefundExecutionService executions;
    private final AfterSaleLineStore lines;

    public AfterSaleRefundExecutor(RefundableQuery query, RefundPlanner planner, ApprovedAmountAllocator allocator,
                                   RefundExecutionService executions, AfterSaleLineStore lines) {
        this.query = query;
        this.planner = planner;
        this.allocator = allocator;
        this.executions = executions;
        this.lines = lines;
    }

    /** Replay returns the frozen plan, never recalculating against partially changed source counters. */
    public void prepare(OrdAfterSale as, OrdSubOrder sub) {
        var prior = executions.inspect(as.getAfterSaleNo());
        if (prior != null) {
            long target = as.getApprovedMinor() == null ? as.getRefundMinor() : as.getApprovedMinor();
            if (!prior.plan().subOrderNo().equals(sub.getSubOrderNo()) || prior.plan().totalMinor() != target) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return;
        }
        RefundableView view = query.view(sub);
        RefundPlan plan = finalPlan(as, view);
        if (plan.isPartial(view) && !view.partialSupported()) {
            throw BizException.of(ErrorCode.AFTERSALE_CHANNEL_PARTIAL_UNSUPPORTED);
        }
        executions.prepare(as.getAfterSaleNo(), plan);
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
