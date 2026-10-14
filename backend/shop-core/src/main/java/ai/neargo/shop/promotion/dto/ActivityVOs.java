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
                                Integer groupHours,
                                // ↓ 自己组合（COMBO）的条件与优惠；其余玩法为空
                                List<RuleItem> rules) {

        /** 不带自己组合的签名（P1a 起的调用方） */
        public ActivityDraft(String activityNo, String name, String goal, String storeNo,
                             String triggerType, Long triggerAmountMinor, Integer triggerQty,
                             String benefitType, Long benefitAmountMinor, Integer benefitQty,
                             String benefitRef,
                             String scheduleType, Long startAt, Long endAt, String scheduleRule,
                             Integer quota, Long budgetMinor,
                             List<AudienceItem> audiences, List<String> goodsNos,
                             String cutoffTime, Integer pickupOffset, String pickupFrom,
                             Integer minQty, Integer periodQuota, Integer decideHours,
                             Integer groupHours) {
            this(activityNo, name, goal, storeNo, triggerType, triggerAmountMinor, triggerQty,
                    benefitType, benefitAmountMinor, benefitQty, benefitRef,
                    scheduleType, startAt, endAt, scheduleRule, quota, budgetMinor,
                    audiences, goodsNos, cutoffTime, pickupOffset, pickupFrom,
                    minQty, periodQuota, decideHours, groupHours, null);
        }

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
                    audiences, goodsNos, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 自己组合的一行（原型 s11）：一个条件或一个优惠。
     *
     * @param kind       CONDITION（全部满足才生效）/ BENEFIT（按顺序叠加）
     * @param type       条件：AMOUNT 满金额 / QTY 满件数 / GOODS 指定商品；优惠：CUT 减 / PERCENT 打折 / POINTS 送积分
     * @param amountMinor AMOUNT 的门槛、CUT 的减额（分）
     * @param n          QTY 的件数、POINTS 的分数
     * @param bp         PERCENT 的折扣（万分比，8000 = 8 折）
     * @param capMinor   PERCENT 的封顶（分），必填
     * @param goodsNos   GOODS 的商品
     */
    public record RuleItem(String kind, String type, Long amountMinor, Integer n, Integer bp,
                           Long capMinor, List<String> goodsNos) {
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
                             Integer groupHours,
                             /** 自己组合的条件与优惠；其余玩法为空列表 */
                             List<RuleItem> rules) {
    }

    /** @param activityName 冲突的那个活动叫什么 —— 只给活动号，商家认不出是哪个 */
    public record ConflictVO(String goodsNo, String activityNo, String activityName,
                             String benefitType) {
    }
}
