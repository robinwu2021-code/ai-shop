package ai.neargo.shop.promotion.dto;

import java.util.List;

/** 活动的 B 端视图与入参（P5）。 */
public final class ActivityVOs {

    private ActivityVOs() {
    }

    /**
     * 建活动 / 改活动。
     *
     * @param audiences <b>空 = 对所有人生效</b>。这条默认值让存量活动迁过来之后行为不变
     * @param goodsNos  作用商品；{@code PRICE} 与 {@code GIFT} 必填
     */
    public record ActivityDraft(String activityNo, String name, String goal, String storeNo,
                                String triggerType, Long triggerAmountMinor, Integer triggerQty,
                                String benefitType, Long benefitAmountMinor, Integer benefitQty,
                                String benefitRef,
                                String scheduleType, Long startAt, Long endAt, String scheduleRule,
                                Integer quota, Long budgetMinor,
                                List<AudienceItem> audiences, List<String> goodsNos,
                                // ↓ 社区集单（CUTOFF）与拼团（GROUP）的参数，其余玩法全为空
                                String cutoffTime, Integer pickupOffset, String pickupFrom,
                                Integer minQty, Integer periodQuota, Integer decideHours,
                                Integer groupHours) {

        /** 不带集单 / 拼团参数的旧签名：存量调用方（含测试）不必跟着改 */
        public ActivityDraft(String activityNo, String name, String goal, String storeNo,
                             String triggerType, Long triggerAmountMinor, Integer triggerQty,
                             String benefitType, Long benefitAmountMinor, Integer benefitQty,
                             String benefitRef,
                             String scheduleType, Long startAt, Long endAt, String scheduleRule,
                             Integer quota, Long budgetMinor,
                             List<AudienceItem> audiences, List<String> goodsNos) {
            this(activityNo, name, goal, storeNo, triggerType, triggerAmountMinor, triggerQty,
                    benefitType, benefitAmountMinor, benefitQty, benefitRef,
                    scheduleType, startAt, endAt, scheduleRule, quota, budgetMinor,
                    audiences, goodsNos, null, null, null, null, null, null, null);
        }
    }

    /** @param type TAG / LEVEL / SOURCE / SEGMENT / NON_MEMBER */
    public record AudienceItem(String type, String value) {
    }

    /**
     * @param liveNow       此刻是不是真的在生效（排期 + 状态 + 还有量）。
     *                      <b>与 status 分开</b>：周期活动在非时段里 status 仍是 RUNNING，
     *                      而商家问的是「现在减不减」
     * @param quotaLeft     还剩多少量。空 = 不限
     * @param maxExposureMinor 最大敞口 = 限量 × 单次优惠。建活动页要显示它
     */
    public record ActivityVO(String activityNo, String name, String goal, String storeNo,
                             String triggerType, Long triggerAmountMinor, Integer triggerQty,
                             String benefitType, Long benefitAmountMinor, Integer benefitQty,
                             String benefitRef,
                             String scheduleType, Long startAt, Long endAt, String scheduleRule,
                             Integer quota, Integer quotaUsed, Integer quotaLeft,
                             Long budgetMinor, Long budgetUsedMinor, Long maxExposureMinor,
                             List<AudienceItem> audiences, List<String> goodsNos,
                             String status, String endedReason, boolean liveNow,
                             String cutoffTime, Integer pickupOffset, String pickupFrom,
                             Integer minQty, Integer periodQuota, Integer decideHours,
                             Integer groupHours) {
    }

    /** @param activityName 冲突的那个活动叫什么 —— 只给活动号，商家认不出是哪个 */
    public record ConflictVO(String goodsNo, String activityNo, String activityName,
                             String benefitType) {
    }
}
