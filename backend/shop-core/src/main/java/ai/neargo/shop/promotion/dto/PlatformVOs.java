package ai.neargo.shop.promotion.dto;

import java.util.List;

/**
 * 平台活动与报名（详细设计 §1.6 · 原型 s27–s30）。
 *
 * <p>出资写成<b>每单金额</b>，不写比例（s28 的约定）：商家看的是「一单我出多少」，
 * 比例要他自己乘，而满减的一单优惠额是固定的，乘出来就是这两个数。
 */
public final class PlatformVOs {

    private PlatformVOs() {
    }

    /**
     * 报名门槛（s29「报名门槛」「类目」「城市」三行）。
     *
     * @param minRating       评分下限；空 = 不限
     * @param noViolation     是否要求无违规（毁约记录为 0）
     * @param categoryNos     只收这些类目的货；空 = 不限
     * @param cityCodes       只收这些城市的店；空 = 不限。<b>P3a 只展示、不校验</b>（商家主体上还没有城市字段）
     */
    public record EnrollRule(Double minRating, boolean noViolation, List<String> categoryNos, List<String> cityCodes) {

        public static EnrollRule none() {
            return new EnrollRule(null, false, List.of(), List.of());
        }
    }

    /**
     * 运营建平台活动（s29）。规则部分与商家活动同一个模型；P3a 只收减钱类玩法（满减 / 满件减 / 立减）。
     *
     * @param platformShareBp 平台出资万分比：10000 全额 / 5000 一半 / 0 不出
     * @param budgetMinor     平台预算（分）。平台出资时必填
     * @param publish         true = 发布报名（商家可见）；false = 存草稿
     */
    public record PlatformDraft(String activityNo, String name,
                                String triggerType, Long triggerAmountMinor, Integer triggerQty,
                                Long benefitAmountMinor,
                                Long startAt, Long endAt, Long enrollDeadline,
                                Integer platformShareBp, Long budgetMinor,
                                EnrollRule enrollRule, boolean publish) {
    }

    /**
     * 一个平台活动。运营端与商家端共用；商家端带上自己的报名（{@code mine}），运营端带上审核计数。
     *
     * @param perOrderPlatformMinor 每单平台最多补贴（分）= 每单优惠 × 出资比例
     * @param perOrderMerchantMinor 每单商家最多承担（分）= 每单优惠 − 平台补贴
     * @param reservedMinor         已通过的报名占掉的预算（s30 右上「预算已占」）
     * @param status                DRAFT（未发布）/ RUNNING（报名中或进行中）/ ENDED
     */
    public record PlatformActivityVO(String activityNo, String name,
                                     String triggerType, Long triggerAmountMinor, Integer triggerQty,
                                     String benefitType, Long benefitAmountMinor,
                                     Long startAt, Long endAt, Long enrollDeadline,
                                     int platformShareBp, Long budgetMinor, long reservedMinor,
                                     long perOrderPlatformMinor, long perOrderMerchantMinor,
                                     EnrollRule enrollRule, String status,
                                     int submitted, int approved, int rejected,
                                     EnrollmentVO mine) {
    }

    /**
     * 一份报名（s28 提交后 · s30 审核表的一行）。
     *
     * @param rating 报名商家的评分（s30「评分」列），审核时判断用
     */
    public record EnrollmentVO(String enrollmentNo, String activityNo, String entityNo, String merchantName,
                               List<String> goodsNos, int quota, int quotaUsed,
                               long platformMaxMinor, long merchantMaxMinor, double rating,
                               String status, String rejectReason, Long reviewedAt, long createdAt) {
    }

    /** 商家报名（s28 下半「报名信息」） */
    public record EnrollCommand(List<String> goodsNos, int quota) {
    }
}
