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
                       String status,
                       /**
                        * 适用范围文案，如「仅限老张粮油店」。**由后端拼**：
                        * 它要把 merchantNo 换成店名，而端上手里只有一个号。
                        * 展示用，实际校验仍在服务端（{@code discountFor}）。
                        */
                       String scopeDesc) {
}
