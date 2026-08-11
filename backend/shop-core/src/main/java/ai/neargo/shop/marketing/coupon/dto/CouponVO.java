package ai.neargo.shop.marketing.coupon.dto;

/** 券模板（对齐契约 Coupon）。 */
public record CouponVO(String couponNo,
                       String title,
                       String type,
                       long faceMinor,
                       int discountRate,
                       long thresholdMinor,
                       long maxDiscountMinor,
                       /** 出资方：PLATFORM / MERCHANT */
                       String funder,
                       String merchantNo,
                       long startAt,
                       long endAt,
                       int remain,
                       boolean received,
                       /** ACTIVE / PAUSED / ENDED。平台列表要靠它筛出被停的券 */
                       String status) {
}
