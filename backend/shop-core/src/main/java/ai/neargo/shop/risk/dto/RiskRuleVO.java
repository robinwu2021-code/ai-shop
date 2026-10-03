package ai.neargo.shop.risk.dto;

/**
 * 拦截规则（对应 ops-web 的 {@code RiskRule}）。
 *
 * @param autoBlock   ⚠️ 这一版**只是配置**，下单/支付链路不读它（TDD-运营端风控域 §二 D3）
 * @param windowHours 契约之外的附加字段：阈值只有配上窗口才有意义
 *                    （「10 单」是一天十单还是一年十单，差别不小）
 */
public record RiskRuleVO(String type,
                         int threshold,
                         boolean autoBlock,
                         int windowHours,
                         String updatedAt) {
}
