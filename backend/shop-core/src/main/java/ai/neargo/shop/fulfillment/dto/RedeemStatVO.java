package ai.neargo.shop.fulfillment.dto;

/**
 * 核销监控（P-5.1.3）：一行 = 一个自提点的履约健康度。
 *
 * <p><b>四个数字全部现算自 {@code ord_sub_order}</b>，平台侧不存计数器
 * （B-6.0 的教训：另存一份迟早出现「总览说 3 单、点进去只有 2 单」）。
 *
 * @param pending  待核销：货还没到点的 + 已到点但仍在宽限期内的
 * @param redeemed 已核销
 * @param overdue  已到点、超过宽限期还没人来取。<b>宽限期就是逾期规则里配的那个</b> ——
 *                 改小了这个数立刻变大，这是那条规则真正被消费的地方
 * @param rate     已核销占比 0–1
 */
public record RedeemStatVO(String pickupNo, String pickupName, String communityName,
                           int pending, int redeemed, int overdue, double rate) {
}
