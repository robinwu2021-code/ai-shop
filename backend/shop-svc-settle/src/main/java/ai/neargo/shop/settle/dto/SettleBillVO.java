package ai.neargo.shop.settle.dto;

/** 结算单（对齐契约 SettleBill）。金额三列都给出去 —— 商家要能自己核对。 */
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
                           Long splitAt) {
}
