package ai.neargo.shop.fulfillment.dto;

/**
 * 一家承运商的接入配置（P-5.2.4）。
 *
 * <p>⚠️ 这一页配错的后果不是「显示不对」，而是<b>订单发不出去</b>。
 *
 * @param enabled          是否启用。不能全停，也不能停掉还有在途单的那家
 * @param priority         数字越小越优先，<b>不允许重复</b>
 * @param accountMasked    接入账号，展示一律脱敏
 * @param apiKeyConfigured 密钥<b>是否</b>已配置。只给布尔而不给密钥本身 ——
 *                         密钥不该出现在前端契约里，哪怕是脱敏的
 * @param pickupCutoff     每日截单时间 HH:mm，过点的单顺延到次日
 * @param slaHours         承诺时效（小时）
 */
public record CarrierConfigVO(String carrier, String name, boolean enabled, int priority,
                              String accountMasked, boolean apiKeyConfigured,
                              String pickupCutoff, int slaHours,
                              String updatedAt, String updatedBy) {
}
