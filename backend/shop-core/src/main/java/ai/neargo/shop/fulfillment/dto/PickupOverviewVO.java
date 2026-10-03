package ai.neargo.shop.fulfillment.dto;

/**
 * 履约总览（B-10.1）。
 *
 * @param serviceFeeMinor 本月履约服务费。**口径未定（R15/B9），一期恒 0** ——
 *                        编一个数字比给 0 更糟：店主会拿它去对账
 */
public record PickupOverviewVO(String pickupNo,
                               String pickupName,
                               int pendingVerify,
                               int arrivedBatches,
                               long serviceFeeMinor) {
}
