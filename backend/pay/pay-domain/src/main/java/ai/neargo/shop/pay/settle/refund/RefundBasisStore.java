package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;

import java.util.Optional;

/** 保存不可变原始基数；准备退款路径消费此事实计算累计切分。 */
public interface RefundBasisStore {
    Optional<SettlementRefundBasis> find(String settleNo);

    /** 新账单金额尚未扣减，与账单的初次插入同事务保存。 */
    void recordCreated(StlBill bill, long originalGoodsPaidMinor);

    /** 已有账单只在没有退款痕迹时补录；有痕迹则记录待核对，不猜原值。 */
    void backfill(StlBill bill, long originalGoodsPaidMinor);
}
