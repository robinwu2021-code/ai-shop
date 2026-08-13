package ai.neargo.shop.marketing.coupon.dto;

/**
 * 建券 / 改券入参（{@code POST /ops/coupons}）。只建平台券——{@code funder} 不开放，
 * 商家券走既有的活动同步（{@code CampaignServiceImpl.syncCoupon}），两条路不重合。
 *
 * @param couponNo         为空新建，非空编辑
 * @param type             {@code FULL_CUT} / {@code DISCOUNT}。NEWCOMER/TARGETED
 *                         还没有折扣算法撑着，此前只在 ops-web 的类型里存在，这里不接
 * @param faceMinor        满减面额（分）。{@code FULL_CUT} 必填
 * @param discountRate     折扣万分比，8500 = 八五折。{@code DISCOUNT} 必填
 * @param maxDiscountMinor 折扣券封顶（分）。{@code DISCOUNT} 必填且必须 &gt;0——
 *                         这是本次改动取消的那个「0=不封顶」取值
 * @param thresholdMinor   使用门槛（分），空即 0
 * @param totalCount       发行量，必须 &gt;0——不限量券的敞口同样算不出来
 * @param perUserLimit     每人限领，空即 1
 * @param budgetMinor      预算（分），空或 0 = 不限；非零时必须 ≥ 最大敞口
 * @param startAt          生效开始（毫秒）
 * @param endAt             生效结束（毫秒），必须晚于 startAt
 */
public record CouponSaveCmd(String couponNo, String title, String type,
                            Long faceMinor, Integer discountRate, Long maxDiscountMinor,
                            Long thresholdMinor, Integer totalCount, Integer perUserLimit,
                            Long budgetMinor, Long startAt, Long endAt) {
}
