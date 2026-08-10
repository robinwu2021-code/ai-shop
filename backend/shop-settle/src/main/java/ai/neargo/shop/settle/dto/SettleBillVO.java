package ai.neargo.shop.settle.dto;

/**
 * 结算单（对齐契约 SettleBill）。金额三列都给出去 —— 商家要能自己核对。
 *
 * <p><b>storeNo 与 payMerchantNo 都要给出去</b>，因为它们回答的是两个不同的问题：
 * 「这笔是哪家店挣的」和「这笔打给哪个账户」。多门店商家两个都要看得见 ——
 * 只给其中一个，他就无法回答「河坊街店这个月的钱到底进了哪张卡」。
 */
public record SettleBillVO(String settleNo,
                           String subOrderNo,
                           String orderNo,
                           String merchantNo,
                           long grossMinor,
                           long commissionMinor,
                           long serviceFeeMinor,
                           long netMinor,
                           String trafficSource,
                           int commissionRate,
                           String status,
                           long createdAt,
                           Long splitAt,
                           /** 哪家店挣的（统计维度）。空 = 存量主体级流水 */
                           String storeNo,
                           /** 打给哪个收款号（结算维度）。空 = 生成时进件还没走完 */
                           String payMerchantNo) {
}
