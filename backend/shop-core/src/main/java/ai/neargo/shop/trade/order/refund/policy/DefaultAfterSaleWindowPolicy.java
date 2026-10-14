package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.order.policy.AfterSaleRules;

/**
 * 完成后 {@code days} 天；质量类原因（损坏 / 质量 / 过期）{@code qualityDays} 天。
 * 天数与结算解冻同一配置（窗口长于结算期 = 钱已分走再退）。
 */
public class DefaultAfterSaleWindowPolicy implements AfterSaleWindowPolicy {

    private static final long DAY_MS = 86_400_000L;
    private final int days;
    private final int qualityDays;

    public DefaultAfterSaleWindowPolicy(int days, int qualityDays) {
        this.days = days;
        this.qualityDays = qualityDays;
    }

    @Override
    public Long deadlineAt(Long completedAt, String reasonCode) {
        if (completedAt == null) {
            return null;
        }
        int d = AfterSaleRules.isQualityReason(reasonCode) ? qualityDays : days;
        return completedAt + d * DAY_MS;
    }
}
