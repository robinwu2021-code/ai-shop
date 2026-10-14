package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;

import java.util.Optional;

/** 写入原始基数；本阶段保留旧退款读路径，后续累计切分才消费该事实。 */
public interface RefundBasisStore {
    Optional<SettlementRefundBasis> find(String settleNo);

    /** 新账单金额尚未扣减，与账单的初次插入同事务保存。 */
    void recordCreated(StlBill bill, long originalGoodsPaidMinor);

    /** 已有账单只在没有退款痕迹时补录；有痕迹则记录待核对，不猜原值。 */
    void backfill(StlBill bill, long originalGoodsPaidMinor);
}
