package ai.neargo.shop.fulfillment.dto;

import java.util.Map;

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
 * @param codes            这家承运商在各物流渠道里叫什么（{@code {"kuaidi100":"shentong","wx":"STO"}}），
 *                         来自物流模块的承运商编码表（TDD-物流模块 O5）。没有的渠道 = 那家渠道不覆盖它
 */
public record CarrierConfigVO(String carrier, String name, boolean enabled, int priority,
                              String accountMasked, boolean apiKeyConfigured,
                              String pickupCutoff, int slaHours,
                              String updatedAt, String updatedBy, Map<String, String> codes) {

    public CarrierConfigVO(String carrier, String name, boolean enabled, int priority,
                           String accountMasked, boolean apiKeyConfigured,
                           String pickupCutoff, int slaHours, String updatedAt, String updatedBy) {
        this(carrier, name, enabled, priority, accountMasked, apiKeyConfigured, pickupCutoff, slaHours,
                updatedAt, updatedBy, Map.of());
    }

    public CarrierConfigVO withCodes(Map<String, String> c) {
        return new CarrierConfigVO(carrier, name, enabled, priority, accountMasked, apiKeyConfigured,
                pickupCutoff, slaHours, updatedAt, updatedBy, c == null ? Map.of() : c);
    }
}
