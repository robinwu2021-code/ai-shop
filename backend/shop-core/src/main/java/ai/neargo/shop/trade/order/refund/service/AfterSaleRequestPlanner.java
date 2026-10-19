package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundableView;
import ai.neargo.shop.trade.order.refund.policy.AfterSaleWindowPolicy;
import ai.neargo.shop.trade.order.refund.policy.ApprovedAmountAllocator;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;

import java.util.List;

/**
 * 买家申请售后时的「退什么、退多少」：售后期守卫 → 按行 × 件数出方案 → 只给了金额就按额分回 → 渠道能否部分退。
 *
 * <p>不传行（老客户端）= 整单剩余；只传金额（老客户端的「部分退」）= 整单剩余按额分回各行。
 */
public class AfterSaleRequestPlanner {

    private final RefundableQuery query;
    private final RefundPlanner planner;
    private final ApprovedAmountAllocator allocator;
    private final AfterSaleWindowPolicy window;
    private final AfterSaleLineStore lines;

    public AfterSaleRequestPlanner(RefundableQuery query, RefundPlanner planner, ApprovedAmountAllocator allocator,
                                   AfterSaleWindowPolicy window, AfterSaleLineStore lines) {
        this.query = query;
        this.planner = planner;
        this.allocator = allocator;
        this.window = window;
        this.lines = lines;
    }

    /**
     * @param shipped     货是否已发出（决定运费）
     * @param reasonCode  原因码（质量类售后期 15 天、已发货运费是否退都看它）
     * @param items       买家选的行；null / 空 = 整单剩余
     * @param refundMinor 老客户端只给金额时的申请额；null = 按方案
     */
    public RefundPlan plan(OrdSubOrder sub, boolean shipped, String reasonCode, List<RefundLine> items,
                           Long refundMinor, long now) {
        Long deadline = window.deadlineAt(sub.getCompletedAt(), reasonCode);
        if (deadline != null && now > deadline) {
            throw BizException.of(ErrorCode.AFTERSALE_WINDOW_CLOSED);
        }
        RefundableView view = query.view(sub);
        RefundPlan plan = planner.plan(view, items, shipped, reasonCode);
        if (refundMinor != null && refundMinor > 0) {
            if (refundMinor > plan.totalMinor()) {
                // 申请额超过可退额：最直接的资损口子，入口挡住
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            plan = allocator.allocate(plan, refundMinor);
        }
        if (plan.isPartial(view) && !view.partialSupported()) {
            throw BizException.of(ErrorCode.AFTERSALE_CHANNEL_PARTIAL_UNSUPPORTED);
        }
        return plan;
    }

    /** Full server-calculated zero cash still represents physical goods and must retain the same window guard. */
    public RefundPlan planFull(OrdSubOrder sub, RefundPlan full, List<RefundLine> items,
                               Long amount, String reasonCode, long now) {
        if (!window.isOpen(sub.getCompletedAt(), reasonCode, now)) {
            throw BizException.of(ErrorCode.AFTERSALE_WINDOW_CLOSED);
        }
        BasicRefundPolicy.validateRequest(full, items, amount);
        return full;
    }

    public void save(String afterSaleNo, RefundPlan plan) {
        lines.saveLines(afterSaleNo, plan.subOrderNo(), plan.lines());
    }
}
