package ai.neargo.shop.promotion.dto;

import java.util.List;

/** 运营侧的券与活动视图（P8）。 */
public final class OpsPromotionVOs {

    private OpsPromotionVOs() {
    }

    /**
     * 运营看到的一张券。
     *
     * @param maxExposureMinor 最大敞口 = 发行量 × 单张最大优惠
     * @param flags            <b>异常标记</b>：{@code NO_BUDGET} 没设预算、
     *                         {@code UNLIMITED} 不限量、{@code BUDGET_TIGHT} 预算快见底、
     *                         {@code HIGH_VALUE} 单张优惠超过阈值。
     *                         平台要在出事之前看见，而商家自己只看得到他那一张
     */
    public record OpsCouponVO(String couponNo, String entityNo, String entityName, String title,
                              String benefitMode, long benefitValue, Long benefitCapMinor,
                              Integer totalCount, int receivedCount, Long budgetMinor,
                              Long maxExposureMinor, String status, List<String> flags) {
    }

    /**
     * 运营看到的一场活动。
     *
     * @param audienceCount 受众条数。<b>0 = 对所有人生效</b> —— 这一列要显示出来，
     *                      「给所有人」与「没设置」在库里长得一样，但含义差很远
     * @param flags         {@code ALWAYS_ON_UNCAPPED} 长期且没限量没预算、
     *                      {@code QUOTA_NEARLY_OUT} 限量快用完、{@code ENDED_BY_QUOTA} 已到量
     */
    public record OpsActivityVO(String activityNo, String entityNo, String entityName,
                                String name, String triggerType, String benefitType,
                                String scheduleType, Integer quota, int quotaUsed,
                                Long budgetMinor, long budgetUsedMinor, int audienceCount,
                                String status, String endedReason, List<String> flags) {
    }
}
