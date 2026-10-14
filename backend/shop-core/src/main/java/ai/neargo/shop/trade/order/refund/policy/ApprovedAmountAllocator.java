package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.order.refund.model.RefundPlan;

/** 商家核定 / 协商的金额怎么分回各行与运费。 */
public interface ApprovedAmountAllocator {

    /** 核定额 ≥ 申请额时原样返回；否则按规则缩到核定额 */
    RefundPlan allocate(RefundPlan requested, long approvedMinor);
}
