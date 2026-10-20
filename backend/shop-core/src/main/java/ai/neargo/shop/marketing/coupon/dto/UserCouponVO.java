package ai.neargo.shop.marketing.coupon.dto;

/** 用户券（对齐契约 UserCoupon）。 */
public record UserCouponVO(String userCouponNo,
                           CouponVO coupon,
                           String status,
                           boolean usableNow,
                           long receivedAt,
                           Long usedAt, String documentNo) {
    public UserCouponVO(String userCouponNo, CouponVO coupon, String status, boolean usableNow, long receivedAt, Long usedAt) {
        this(userCouponNo, coupon, status, usableNow, receivedAt, usedAt, null);
    }
    public UserCouponVO withDocumentNo(String number) {
        return new UserCouponVO(userCouponNo, coupon, status, usableNow, receivedAt, usedAt, number);
    }

}
